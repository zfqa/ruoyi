package com.ruoyi.business.vehicle.config;

import com.ruoyi.business.vehicle.mapper.VehicleCollectTaskMapper;
import org.slf4j.Logger; import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments; import org.springframework.boot.ApplicationRunner; import org.springframework.stereotype.Component;

@Component
public class VehicleCollectStartupMaintenance implements ApplicationRunner
{
    private static final Logger LOG = LoggerFactory.getLogger(VehicleCollectStartupMaintenance.class);
    private final VehicleCollectTaskMapper mapper;
    public VehicleCollectStartupMaintenance(VehicleCollectTaskMapper mapper) { this.mapper = mapper; }
    @Override public void run(ApplicationArguments args) { try { int count=mapper.markInterruptedRunningTasks(); if(count>0) LOG.warn("Reconciled {} interrupted vehicle collect task(s)", count); } catch(Exception e) { LOG.error("Vehicle collect startup maintenance failed; startup continues", e); } }
}
