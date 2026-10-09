package com.ruoyi.business.knowledge.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import com.ruoyi.business.vehicle.domain.VehicleCollectTask;
import com.ruoyi.business.vehicle.domain.VehicleModel;
import com.ruoyi.business.vehicle.mapper.VehicleCollectTaskMapper;
import com.ruoyi.business.vehicle.mapper.VehicleModelMapper;

/**
 * 图谱车企/车型实体目录：以懂车帝已采集数据为权威补充，并提供中英别名归一。
 * 懂车帝库内多为中文名；此处为 TARGET_BRANDS 补英文别名，保证 Chery↔奇瑞、BYD↔比亚迪 等同实体。
 */
@Component
public class KnowledgeVehicleCatalog
{
    /** 懂车帝爬虫品牌白名单的中英对照（与 agent-service TARGET_BRANDS 对齐）。 */
    private static final Map<String, List<String>> DONGCHEDI_BRAND_ALIASES = dongchediBrandAliases();

    private final VehicleModelMapper modelMapper;
    private final VehicleCollectTaskMapper taskMapper;

    private volatile Map<String, List<String>> companies = Map.copyOf(DONGCHEDI_BRAND_ALIASES);
    private volatile Set<String> models = Set.of();
    private volatile Map<String, String> aliasToCanonicalCompany = Map.of();
    private volatile Map<String, String> aliasToCanonicalModel = Map.of();

    @Autowired
    public KnowledgeVehicleCatalog(VehicleModelMapper modelMapper, VehicleCollectTaskMapper taskMapper)
    {
        this.modelMapper = modelMapper;
        this.taskMapper = taskMapper;
    }

    /** 无 Spring / 无库时的静态种子（单测可用）。 */
    public KnowledgeVehicleCatalog()
    {
        this.modelMapper = null;
        this.taskMapper = null;
        this.companies = Map.copyOf(DONGCHEDI_BRAND_ALIASES);
        this.models = Set.copyOf(seedModels());
        rebuildAliasIndexes();
    }

    @PostConstruct
    public void refreshFromDongchedi()
    {
        Map<String, List<String>> companyMap = new LinkedHashMap<>(DONGCHEDI_BRAND_ALIASES);
        Set<String> modelSet = new LinkedHashSet<>(seedModels());
        try
        {
            if (modelMapper != null)
            {
                List<VehicleModel> rows = modelMapper.selectList(new VehicleModel());
                if (rows != null)
                {
                    for (VehicleModel row : rows)
                    {
                        addBrand(companyMap, row.getBrandName());
                        addModelTerms(modelSet, row.getSeriesName());
                        addModelTerms(modelSet, row.getModelName());
                    }
                }
            }
            if (taskMapper != null)
            {
                List<VehicleCollectTask> tasks = taskMapper.selectList(new VehicleCollectTask());
                if (tasks != null)
                {
                    for (VehicleCollectTask task : tasks)
                    {
                        addBrand(companyMap, task.getBrandName());
                        addModelTerms(modelSet, task.getSeriesName());
                    }
                }
            }
        }
        catch (Exception ignored)
        {
            // 库不可用时保留种子，不影响启动
        }
        this.companies = Map.copyOf(companyMap);
        this.models = Set.copyOf(modelSet);
        rebuildAliasIndexes();
    }

    public Map<String, List<String>> companyAliases()
    {
        return companies;
    }

    public Set<String> modelTerms()
    {
        return models;
    }

    /** 将任意中英文别名归一到规范企业名；无法识别则返回原文 trim。 */
    public String canonicalizeCompany(String raw)
    {
        if (raw == null || raw.isBlank()) return raw;
        String key = normalizeKey(raw);
        String canonical = aliasToCanonicalCompany.get(key);
        return canonical != null ? canonical : raw.trim();
    }

    public String companyAliasCsv(String canonicalOrRaw)
    {
        String canonical = canonicalizeCompany(canonicalOrRaw);
        List<String> aliases = companies.get(canonical);
        if (aliases == null || aliases.isEmpty()) return canonical;
        return String.join(",", aliases);
    }

    /** 将车型别名归一；无法识别则返回原文 trim。 */
    public String canonicalizeModel(String raw)
    {
        if (raw == null || raw.isBlank()) return raw;
        String key = normalizeKey(raw);
        String canonical = aliasToCanonicalModel.get(key);
        return canonical != null ? canonical : raw.trim();
    }

    public boolean isKnownCompanyAlias(String raw)
    {
        return raw != null && aliasToCanonicalCompany.containsKey(normalizeKey(raw));
    }

    private void rebuildAliasIndexes()
    {
        Map<String, String> companyIndex = new ConcurrentHashMap<>();
        for (Map.Entry<String, List<String>> entry : companies.entrySet())
        {
            companyIndex.put(normalizeKey(entry.getKey()), entry.getKey());
            for (String alias : entry.getValue())
                companyIndex.put(normalizeKey(alias), entry.getKey());
        }
        Map<String, String> modelIndex = new ConcurrentHashMap<>();
        for (String model : models)
        {
            modelIndex.put(normalizeKey(model), model);
            // 配置款「元UP 2025款 …」也能命中车系「元UP」
            int cut = model.indexOf(' ');
            if (cut > 1)
            {
                String head = model.substring(0, cut).trim();
                if (head.length() >= 2) modelIndex.putIfAbsent(normalizeKey(head), head);
            }
        }
        this.aliasToCanonicalCompany = Map.copyOf(companyIndex);
        this.aliasToCanonicalModel = Map.copyOf(modelIndex);
    }

    private static void addBrand(Map<String, List<String>> companyMap, String brandName)
    {
        if (brandName == null || brandName.isBlank()) return;
        String cn = brandName.trim();
        // 已有英文规范名则把中文并入别名
        for (Map.Entry<String, List<String>> entry : new ArrayList<>(companyMap.entrySet()))
        {
            if (entry.getValue().stream().anyMatch(a -> a.equalsIgnoreCase(cn) || a.equals(cn)))
            {
                if (entry.getValue().stream().noneMatch(a -> a.equals(cn)))
                {
                    List<String> next = new ArrayList<>(entry.getValue());
                    next.add(cn);
                    companyMap.put(entry.getKey(), List.copyOf(next));
                }
                return;
            }
        }
        companyMap.putIfAbsent(cn, List.of(cn));
    }

    private static void addModelTerms(Set<String> modelSet, String raw)
    {
        if (raw == null || raw.isBlank()) return;
        String value = raw.trim();
        modelSet.add(value);
        // 车系短名：去掉年份/款式后缀前的主体
        String shortName = value.replaceAll("\\s*20\\d{2}款.*$", "").trim();
        if (shortName.length() >= 2 && shortName.length() <= 40) modelSet.add(shortName);
        int space = shortName.indexOf(' ');
        if (space > 1) modelSet.add(shortName.substring(0, space).trim());
    }

    private static String normalizeKey(String value)
    {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("[\\s·._—–\\-]+", "");
    }

    /** 全量企业中英别名种子（懂车帝目标品牌 + 面板/供应链 + 常见主机厂）。 */
    private static Map<String, List<String>> dongchediBrandAliases()
    {
        Map<String, List<String>> values = new LinkedHashMap<>();
        // 懂车帝目标车企
        values.put("BYD", List.of("BYD", "比亚迪", "byd"));
        values.put("Chery", List.of("Chery", "奇瑞", "奇瑞汽车", "CHERY"));
        values.put("NIO", List.of("NIO", "蔚来", "Nio"));
        values.put("Li Auto", List.of("Li Auto", "LiAuto", "理想", "理想汽车"));
        values.put("Changan", List.of("Changan", "长安", "长安汽车", "CHANGAN"));
        values.put("Geely", List.of("Geely", "吉利", "吉利汽车"));
        values.put("Great Wall", List.of("Great Wall", "GWM", "长城", "长城汽车"));
        // 其他主机厂
        values.put("Tesla", List.of("Tesla", "特斯拉"));
        values.put("XPeng", List.of("XPeng", "小鹏"));
        values.put("SAIC", List.of("SAIC", "上汽"));
        values.put("GAC", List.of("GAC", "广汽"));
        values.put("BMW", List.of("BMW", "宝马"));
        values.put("Mercedes-Benz", List.of("Mercedes-Benz", "奔驰"));
        values.put("Volkswagen", List.of("Volkswagen", "大众"));
        // 面板 / 供应链
        values.put("Tianma", List.of("Tianma", "天马"));
        values.put("AUO", List.of("AUO", "友达"));
        values.put("BOE", List.of("BOE", "京东方"));
        values.put("CSOT", List.of("CSOT", "China Star", "华星"));
        values.put("Faurecia", List.of("Faurecia", "佛吉亚"));
        values.put("Continental", List.of("Continental", "大陆集团", "大陆"));
        return values;
    }

    /** 种子车型：在库为空时仍覆盖懂车帝常见系列写法。 */
    private static List<String> seedModels()
    {
        return List.of(
            "元UP", "大唐EV", "宋Pro DM", "宋Pro",
            "海狮05EV", "海狮07DM-i", "海狮06", "海狮07", "海狮05", "海狮",
            "海豹06DM", "海豹06", "海豹", "海豚", "海鸥",
            "秦PLUS", "秦L EV", "秦L", "宋PLUS", "元PLUS", "汉L", "唐L", "汉", "唐", "夏", "DM-i",
            "腾势N8L", "腾势N9", "腾势N7", "腾势D9", "腾势",
            "仰望U9", "仰望U8", "仰望U7", "仰望",
            "方程豹", "豹8", "豹5",
            "艾瑞泽8", "艾瑞泽", "瑞虎", "风云", "星途", "捷途", "iCAR", "瑶光",
            "理想L9", "理想L8", "理想L7", "理想L6", "蔚来EVE", "ET5", "ET7", "ES6", "ES8",
            "UNI-V", "UNI-K", "CS75", "深蓝SL03", "哈弗H6", "坦克300", "银河L7", "极氪001"
        );
    }
}
