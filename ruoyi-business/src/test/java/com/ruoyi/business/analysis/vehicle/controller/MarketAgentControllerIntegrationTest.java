package com.ruoyi.business.analysis.vehicle.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.business.analysis.vehicle.domain.VehicleAnalysis;
import com.ruoyi.business.analysis.vehicle.service.IVehicleAnalysisService;
import com.ruoyi.business.analysis.vehicle.service.MarketAgentGateway;
import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.common.core.domain.model.LoginUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * 本机完整链路回归：浏览器等价的 multipart -> Java 网关 -> Python 异步任务
 * -> 数据集登记 -> 工作表/周期/分析。默认不连接外部进程，启动本机 Market
 * Agent 后通过 RUN_MARKET_AGENT_IT=true 显式执行。
 */
@EnabledIfEnvironmentVariable(named = "RUN_MARKET_AGENT_IT", matches = "true")
class MarketAgentControllerIntegrationTest
{
    private final InMemoryVehicleAnalysisService records = new InMemoryVehicleAnalysisService();
    private final MarketAgentController controller = new MarketAgentController(
        new MarketAgentGateway("http://127.0.0.1:8001/api", 5, 120), records);

    @BeforeEach
    void authenticateLocalAdmin()
    {
        SysUser user = new SysUser(1L);
        user.setUserName("admin");
        LoginUser loginUser = new LoginUser(1L, 103L, user, Set.of("*:*:*"));
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(loginUser, null, loginUser.getAuthorities()));
    }

    @AfterEach
    void clearSecurityContext()
    {
        SecurityContextHolder.clearContext();
    }

    @Test
    void uploadPollAndAnalyzeCompletesThroughJavaGateway() throws Exception
    {
        String configuredFile = System.getenv("MARKET_AGENT_IT_FILE");
        Path source = configuredFile == null || configuredFile.isBlank()
            ? Path.of("market-agent", "data", "sample_timeseries_market.csv")
            : Path.of(configuredFile);
        String contentType = source.getFileName().toString().endsWith(".csv")
            ? "text/csv" : "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        MockMultipartFile file = new MockMultipartFile("files", source.getFileName().toString(),
            contentType, Files.readAllBytes(source));

        ResponseEntity<String> created = controller.createUploadJob(new MockMultipartFile[] { file });
        JSONObject job = JSON.parseObject(created.getBody());
        assertNotNull(job.getString("job_id"), created.getBody());

        String datasetId = null;
        for (int attempt = 0; attempt < 40; attempt++)
        {
            Thread.sleep(250L);
            JSONObject polled = JSON.parseObject(controller.uploadJob(job.getString("job_id")).getBody());
            if ("failed".equals(polled.getString("status")))
            {
                throw new AssertionError(polled.toJSONString());
            }
            if ("success".equals(polled.getString("status")))
            {
                datasetId = polled.getJSONObject("result").getString("dataset_id");
                break;
            }
        }
        assertNotNull(datasetId, "上传解析任务未在 10 秒内完成");

        try
        {
            JSONObject sheets = JSON.parseObject(controller.sheets(datasetId).getBody());
            assertFalse(sheets.getJSONArray("sheets").isEmpty());

            MockHttpServletRequest request = new MockHttpServletRequest();
            request.setQueryString("period_mode=latest");
            JSONObject periods = JSON.parseObject(controller.periodOptions(datasetId, request).getBody());
            assertNotNull(periods.getString("latest_period"), periods.toJSONString());

            JSONObject analysis = JSON.parseObject(controller.analysis(datasetId, request).getBody());
            assertFalse(analysis.getJSONArray("overview_table").isEmpty());
            assertEquals(datasetId, records.selectVehicleAnalysisByDatasetId(datasetId).getDatasetId());
        }
        finally
        {
            controller.deleteDataset(datasetId);
        }
    }

    @Test
    void excelParseJobReturnsPreviewWithoutRegisteringAnalysisDataset() throws Exception
    {
        Path source = Path.of("market-agent", "data", "sample_timeseries_market.csv");
        MockMultipartFile file = new MockMultipartFile("files", source.getFileName().toString(),
            "text/csv", Files.readAllBytes(source));

        ResponseEntity<String> created = controller.createExcelParseJob(new MockMultipartFile[] { file });
        JSONObject job = JSON.parseObject(created.getBody());
        assertNotNull(job.getString("job_id"), created.getBody());

        JSONObject result = null;
        for (int attempt = 0; attempt < 40; attempt++)
        {
            Thread.sleep(250L);
            JSONObject polled = JSON.parseObject(controller.excelParseJob(job.getString("job_id")).getBody());
            if ("failed".equals(polled.getString("status")))
            {
                throw new AssertionError(polled.toJSONString());
            }
            if ("success".equals(polled.getString("status")))
            {
                result = polled.getJSONObject("result");
                break;
            }
        }

        assertNotNull(result, "Excel 临时解析任务未在 10 秒内完成");
        assertFalse(result.containsKey("dataset_id"), "Excel 导入预览不应暴露市场分析数据集ID");
        assertFalse(result.getJSONArray("file_results").isEmpty(), result.toJSONString());
        assertEquals(0, records.selectVehicleAnalysisList(new VehicleAnalysis()).size(),
            "Excel 导入预览不应创建市场分析数据库记录");
    }

    private static class InMemoryVehicleAnalysisService implements IVehicleAnalysisService
    {
        private final AtomicLong ids = new AtomicLong();
        private final Map<Long, VehicleAnalysis> rows = new LinkedHashMap<>();

        @Override
        public List<VehicleAnalysis> selectVehicleAnalysisList(VehicleAnalysis filter)
        {
            return new ArrayList<>(rows.values());
        }

        @Override
        public VehicleAnalysis selectVehicleAnalysisById(Long id) { return rows.get(id); }

        @Override
        public VehicleAnalysis selectVehicleAnalysisByDatasetId(String datasetId)
        {
            return rows.values().stream().filter(row -> datasetId.equals(row.getDatasetId())).findFirst().orElse(null);
        }

        @Override
        public int insertVehicleAnalysis(VehicleAnalysis row)
        {
            row.setId(ids.incrementAndGet());
            rows.put(row.getId(), row);
            return 1;
        }

        @Override
        public int updateVehicleAnalysis(VehicleAnalysis row)
        {
            rows.put(row.getId(), row);
            return 1;
        }

        @Override
        public int deleteVehicleAnalysisById(Long id) { return rows.remove(id) == null ? 0 : 1; }

        @Override
        public int deleteVehicleAnalysisByIds(Long[] ids)
        {
            int count = 0;
            for (Long id : ids) count += deleteVehicleAnalysisById(id);
            return count;
        }

        @Override
        public int deleteVehicleAnalysisByDatasetId(String datasetId)
        {
            VehicleAnalysis row = selectVehicleAnalysisByDatasetId(datasetId);
            return row == null ? 0 : deleteVehicleAnalysisById(row.getId());
        }
    }
}
