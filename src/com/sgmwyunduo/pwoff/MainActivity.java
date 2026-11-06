package com.sgmwyunduo.pwoff;

import android.app.Activity;
import android.os.Bundle;
import android.widget.Toast;

public class MainActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        GuardService.schedulePersistedJob(this);
        GuardService.start(this, "manual");
        Toast.makeText(this, "行人警示自动关闭已启动（云朵版）", Toast.LENGTH_LONG).show();
        finish();
    }
}
