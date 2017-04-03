package com.example.donhatch.rotationlockadaptiveshim;

import android.provider.Settings;

public class TheActivity extends android.app.Activity {

    // We set this temporarily while setting the checkbox from the program,
    // so that the onCheckedChanged listener can tell that's what happened.
    private boolean mSettingCheckedFromProgram = false;
    private long mNumUpdates = 0;

    private android.os.Handler mHandler = new android.os.Handler();
    private Runnable mRunnable = new Runnable() {
        @Override
        public void run() {
            System.out.println("                in monitor run");
            updateStatusTextField();
            mHandler.postDelayed(this, 1*1000);
            System.out.println("                out monitor run");
        };
    };

    public TheActivity() {
        System.out.println("in TheActivity ctor");
        System.out.println("out TheActivity ctor");
    }


    @Override
    protected void onCreate(android.os.Bundle savedInstanceState) {
        System.out.println("    in onCreate");
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        android.widget.Switch theServiceSwitch = (android.widget.Switch)findViewById(R.id.theServiceSwitch);
        android.widget.Switch theMonitorSwitch = (android.widget.Switch)findViewById(R.id.theMonitorSwitch);
        if (true) {
            theMonitorSwitch.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
                public void onCheckedChanged(android.widget.CompoundButton buttonView, boolean isChecked) {
                    System.out.println("            in theMonitorSwitch onCheckedChanged(isChecked=" + isChecked + ")");
                    mHandler.removeCallbacks(mRunnable); // ok if it wasn't scheduled
                    if (isChecked) {
                        mHandler.postDelayed(mRunnable, 0); // immediately
                    }
                    System.out.println("            out theMonitorSwitch onCheckedChanged(isChecked=" + isChecked + ")");
                }
            });
        }
        if (true) {
            theServiceSwitch.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
                public void onCheckedChanged(android.widget.CompoundButton buttonView, boolean isChecked) {
                    System.out.println("            in theServiceSwitch onCheckedChanged(isChecked=" + isChecked + ")");
                    if (mSettingCheckedFromProgram) {
                        System.out.println("              (from program; not doing anything)");
                    } else {
                        if (isChecked) {
                            System.out.println("              calling startService");
                            startService(new android.content.Intent(TheActivity.this, TheService.class));
                            System.out.println("              returned from startService");
                            if (false) {
                                // Make sure we can't mess up service's notion of whether it's running,
                                // by sending a whole flurry of stuff.
                                // NOTE: this does seem to confuse the notification icon! or, it did, before I started checking for mSettingCheckedFromProgram
                                startService(new android.content.Intent(TheActivity.this, TheService.class));
                                stopService(new android.content.Intent(TheActivity.this, TheService.class));
                                stopService(new android.content.Intent(TheActivity.this, TheService.class));
                                startService(new android.content.Intent(TheActivity.this, TheService.class));
                                startService(new android.content.Intent(TheActivity.this, TheService.class));
                            }
                            System.out.println("              setting text to \"Service is on  \"");
                            buttonView.setText("Service is on  "); // we assume startService is reliable
                        } else {
                            System.out.println("              calling stopService");
                            stopService(new android.content.Intent(TheActivity.this, TheService.class));
                            System.out.println("              returned from stopService");
                            if (false) {
                                // Make sure we can't mess up service's notion of whether it's running,
                                // by sending a whole flurry of stuff
                                // NOTE: this does seem to confuse the notification icon!
                                stopService(new android.content.Intent(TheActivity.this, TheService.class));
                                startService(new android.content.Intent(TheActivity.this, TheService.class));
                                startService(new android.content.Intent(TheActivity.this, TheService.class));
                                stopService(new android.content.Intent(TheActivity.this, TheService.class));
                                stopService(new android.content.Intent(TheActivity.this, TheService.class));
                            }
                            System.out.println("              setting text to \"Service is off \"");
                            buttonView.setText("Service is off "); // we assume stopService is reliable
                        }
                    }
                    System.out.println("            out theServiceSwitch onCheckedChanged(isChecked=" + isChecked + ")");
                }
            });
        }
        // Double-opt-in dance needed to write settings.
        // It causes the appropriate permissions screen to come up if it's wrong.
        // (Can also manually grant/ungrant by Settings -> Apps -> <this app> -> Modify system settings -> Yes/No, *if* activity is not running. (Force Stop first if it is))
        // Actually I think I can grant/ungrant on the fly, but the Settings switch gets out of sync with what it really is.  This is a reported bug, I think.
        if (!android.provider.Settings.System.canWrite(this)) {
            android.content.Intent grantIntent = new android.content.Intent(android.provider.Settings.ACTION_MANAGE_WRITE_SETTINGS);
            grantIntent.setData(android.net.Uri.parse("package:"+getPackageName()));
            System.out.println("              grantIntent = "+grantIntent);
            System.out.println("              calling startActivity with ACTION_MANAGE_WRITE_SETTINGS");
            startActivity(grantIntent);
            System.out.println("              returned from startActivity with ACTION_MANAGE_WRITE_SETTINGS");
        } else {
            System.out.println("              can already modify system settings.  Cool.");
        }
        System.out.println("    out onCreate");
    }

    private void updateStatusTextField() {
        android.widget.TextView theTextView = (android.widget.TextView)findViewById(R.id.theTextView);
        int accelerometerRotation = -1;
        boolean gotAccelerometerRotation = false;
        int userRotation = -1;
        boolean gotUserRotation = false;
        try {
            accelerometerRotation = Settings.System.getInt(getContentResolver(), Settings.System.ACCELEROMETER_ROTATION);
            gotAccelerometerRotation = true;
        } catch (Settings.SettingNotFoundException e) {}
        try {
            userRotation = Settings.System.getInt(getContentResolver(), Settings.System.USER_ROTATION);
            gotUserRotation = true;
        } catch (Settings.SettingNotFoundException e) {}
        String message = "";
        message += ("getRequestedOrientation() = " + TheService.orientationConstantToString(getRequestedOrientation()));
        message += "\n\n";
        message += (gotAccelerometerRotation ? "Settings.System.ACCELEROMETER_ROTATION = "+accelerometerRotation : "[no Settings.System.ACCELEROMETER_ROTATION]");
        message += "\n\n";
        message += (gotUserRotation ? "Settings.System.USER_ROTATION = "+TheService.surfaceRotationConstantToString(userRotation) : "[no Settings.System.USER_ROTATION]");
        message += "\n\n";
        message += ("getResources().getConfiguration().orientation = " + TheService.orientationConstantToString(getResources().getConfiguration().orientation));
        message += "\n\n";
        message += ("getWindowManager().getDefaultDisplay().getRotation() = " + TheService.surfaceRotationConstantToString(getWindowManager().getDefaultDisplay().getRotation()));
        message += "\n\n";
        mNumUpdates++;
        message += ""+mNumUpdates+" update"+(mNumUpdates==1?"":"s");
        theTextView.setText(message);
    }

    @Override
    protected void onStart() {
        System.out.println("        in onStart");
        super.onStart();
        android.widget.Switch theServiceSwitch = (android.widget.Switch)findViewById(R.id.theServiceSwitch);

        updateStatusTextField();

        boolean serviceIsRunning = TheService.theRunningService != null;
        System.out.println("          calling theServiceSwitch.setChecked("+serviceIsRunning+")");
        mSettingCheckedFromProgram = true;
        theServiceSwitch.setChecked(serviceIsRunning);
        mSettingCheckedFromProgram = false;
        System.out.println("          returned from theServiceSwitch.setChecked("+serviceIsRunning+")");
        // That invoked the listener which set the label to "Service is on" or "Service is off";
        // overwrite it with something that says "initially".
        if (serviceIsRunning) {
            System.out.println("          setting text to \"Service is initially on  \"");
            theServiceSwitch.setText("Service is initially on  ");
        } else {
            System.out.println("          setting text to \"Service is initially off \"");
            theServiceSwitch.setText("Service is initially off ");
        }
        System.out.println("        out onStart");
    }

    @Override
    protected void onResume() {
        System.out.println("            in onResume");
        super.onResume();

        android.widget.Switch theMonitorSwitch = (android.widget.Switch)findViewById(R.id.theMonitorSwitch);
        if (theMonitorSwitch.isChecked()) {
            mHandler.postDelayed(mRunnable, 1*1000);
        }

        if (false) {
            // Test whether event loop keeps going after activity closed.  Yes, it does.
            getMainLooper().dump(new android.util.Printer() {
                @Override
                public void println(String s) {
                    System.out.println("- "+s);
                }
            }, "foo: ");
            System.out.println("          setting something to go off in 10 seconds");
            android.os.Handler handler = new android.os.Handler();
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    System.out.println("          in delayed run");
                    getMainLooper().dump(new android.util.Printer() {
                        @Override
                        public void println(String s) {
                            System.out.println("- "+s);
                        }
                    }, "foo: ");
                    System.out.println("          out delayed run");
                }
            }, 10*1000);
            getMainLooper().dump(new android.util.Printer() {
                @Override
                public void println(String s) {
                    System.out.println("- "+s);
                }
            }, "foo: ");
        }
        System.out.println("            out onResume");
    }

    @Override
    protected void onPause() {
        System.out.println("            in onPause");
        mHandler.removeCallbacks(mRunnable); // ok if it wasn't scheduled
        super.onPause();
        System.out.println("            out onPause");
    }

    @Override
    protected void onStop() {
        System.out.println("        in onStop");
        super.onStop();
        System.out.println("        out onStop");
    }

    @Override
    protected void onDestroy() {
        System.out.println("    in onDestroy");
        super.onDestroy();
        System.out.println("    out onDestroy");
    }

    // Note, in order for this to be called (rather than the system stopping and restarting
    // the activity), the manifest must have "orientation|screenSize" in android:configChanges
    @Override
    public void onConfigurationChanged(android.content.res.Configuration newConfig) {
        System.out.println("in onConfigurationChanged");
        super.onConfigurationChanged(newConfig);
        //setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        System.out.println("out onConfigurationChanged");
    }
}
