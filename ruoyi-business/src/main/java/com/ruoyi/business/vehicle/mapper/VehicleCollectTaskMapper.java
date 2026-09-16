package com.ruoyi.business.vehicle.mapper;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import com.ruoyi.business.vehicle.domain.VehicleCollectTask;
@Mapper public interface VehicleCollectTaskMapper {
    List<VehicleCollectTask> selectList(VehicleCollectTask query); VehicleCollectTask selectById(Long id);
    int insert(VehicleCollectTask task); int update(VehicleCollectTask task); int markInterruptedRunningTasks();
}
