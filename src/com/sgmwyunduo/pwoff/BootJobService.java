package com.sgmwyunduo.pwoff;

import android.app.job.JobParameters;
import android.app.job.JobService;

/**
 * 持久化 JobService：由系统 JobScheduler 在开机后拉起，不依赖 BOOT_COMPLETED 广播投递。
 */
public class BootJobService extends JobService {
    @Override
    public boolean onStartJob(JobParameters params) {
        GuardService.start(this, "job");
        jobFinished(params, false);
        return false;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return false;
    }
}
