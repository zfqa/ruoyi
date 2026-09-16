package com.ruoyi.business.vehicle.mapper;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import com.ruoyi.business.vehicle.domain.VehicleModel;
@Mapper public interface VehicleModelMapper {
    List<VehicleModel> selectList(VehicleModel query); VehicleModel selectById(Long id);
    VehicleModel selectBySourceCarId(@Param("sourceCode") String sourceCode, @Param("carId") String carId);
    List<VehicleModel> selectByLegacyTask(@Param("taskId") Long taskId, @Param("sourceCode") String sourceCode, @Param("seriesId") String seriesId);
    int insert(VehicleModel model); int update(VehicleModel model); int updateLastSeen(VehicleModel model);
}
