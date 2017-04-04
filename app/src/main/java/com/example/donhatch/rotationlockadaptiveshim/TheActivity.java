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
            System.out.println("                in once-per-second poll, should only be when ui is visible");
            updateAccelerometerOrientationDegreesTextView(); // XXX not sure about this
            updatePolledStatusTextView();
            mHandler.postDelayed(this, 1*1000);
            System.out.println("                out once-per-second poll, should only be when ui is visible");
        };
    };

    private android.content.BroadcastReceiver mBroadcastReceiver = new android.content.BroadcastReceiver() {
        @Override
        public void onReceive(android.content.Context context, android.content.Intent intent) {
            //System.out.println("                in onReceive");
            int oldDegrees = intent.getIntExtra("oldDegrees", -100);
            int newDegrees = intent.getIntExtra("newDegrees", -100);
            System.out.println("                  intent.getIntExtra(\"oldDegrees\") = "+oldDegrees);
            System.out.println("                  intent.getIntExtra(\"newDegrees\") = "+newDegrees);
            android.widget.TextView theAccelerometerOrientationDegreesTextView = (android.widget.TextView)findViewById(R.id.theAccelerometerOrientationDegreesTextView); // XXX TODO: make this a member
            theAccelerometerOrientationDegreesTextView.setText("  accelerometer degrees (most recent update): "+oldDegrees+" -> "+newDegrees);
            //System.out.println("                out onReceive");
        }
    };  // mBroadcastReceiver

    private android.database.ContentObserver mAccelerometerRotationObserver = new android.database.ContentObserver(new android.os.Handler()) {
        // Per https://developer.android.com/reference/android/database/ContentObserver.html :
        // Delegate to ensure correct operation on older versions of the framework
        // that didn't have the onChange(boolean, Uri) method. (XXX do I need to worry about this? is framework runtime, or my compiletime?)
        @Override
        public void onChange(boolean selfChange) {
            System.out.println("            in TheActivity mAccelerometerRotationObserver onChange(selfChange="+selfChange+")");
            onChange(selfChange, null);
            System.out.println("            out TheActivity mAccelerometerRotationObserver onChange(selfChange="+selfChange+")");
        }
        @Override
        public void onChange(boolean selfChange, android.net.Uri uri) {
            System.out.println("            in TheActivity mAccelerometerRotationObserver onChange(selfChange="+selfChange+", uri="+uri+")");
            try {
                System.out.println("              Settings.System.ACCELEROMETER_ROTATION: "+Settings.System.getInt(getContentResolver(), Settings.System.ACCELEROMETER_ROTATION));
            } catch (Settings.SettingNotFoundException e) {
                System.out.println("              Settings.System.ACCELEROMETER_ROTATION setting not found!?");
            }
            try {
                System.out.println("              (Settings.System.USER_ROTATION: "+TheService.surfaceRotationConstantToString(Settings.System.getInt(getContentResolver(), Settings.System.USER_ROTATION))+")");

            } catch (Settings.SettingNotFoundException e) {
                System.out.println("              (Settings.System.USER_ROTATION setting not found!?)");
            }
            updateAccelerometerRotationTextView();
            System.out.println("            out TheActivity mAccelerometerRotationObserver onChange(selfChange="+selfChange+", uri="+uri+")");
        }
    };  // mAccelerometerRotationObserver
    android.database.ContentObserver mUserRotationObserver = new android.database.ContentObserver(new android.os.Handler()) {
        // Per https://developer.android.com/reference/android/database/ContentObserver.html :
        // Delegate to ensure correct operation on older versions of the framework
        // that didn't have the onChange(boolean, Uri) method. (XXX do I need to worry about this? is framework runtime, or my compiletime?)
        @Override
        public void onChange(boolean selfChange) {
            System.out.println("            in TheActivity mUserRotationObserver onChange(selfChange="+selfChange+")");
            onChange(selfChange, null);
            System.out.println("            out TheActivity mUserRotationObserver onChange(selfChange="+selfChange+")");
        }
        @Override
        public void onChange(boolean selfChange, android.net.Uri uri) {
            System.out.println("            in TheActivity mUserRotationObserver onChange(selfChange="+selfChange+", uri="+uri+")");
            try {
                System.out.println("              (Settings.System.ACCELEROMETER_ROTATION: "+Settings.System.getInt(getContentResolver(), Settings.System.ACCELEROMETER_ROTATION)+")");
            } catch (Settings.SettingNotFoundException e) {
                System.out.println("              (Settings.System.ACCELEROMETER_ROTATION setting not found!?)");
            }
            try {
                System.out.println("              Settings.System.USER_ROTATION: "+TheService.surfaceRotationConstantToString(Settings.System.getInt(getContentResolver(), Settings.System.USER_ROTATION)));

            } catch (Settings.SettingNotFoundException e) {
                System.out.println("              Settings.System.USER_ROTATION setting not found!?");
            }
            updateUserRotationTextView();
            System.out.println("            out TheActivity mUserRotationObserver onChange(selfChange="+selfChange+", uri="+uri+")");
        }
    };  // mUserRotationObserver

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
        android.widget.Switch theWhackAMoleSwitch = (android.widget.Switch)findViewById(R.id.theWhackAMoleSwitch);
        android.widget.Switch theAutoRotateSwitch = (android.widget.Switch)findViewById(R.id.theAutoRotateSwitch);
        android.widget.Switch theMonitorSwitch = (android.widget.Switch)findViewById(R.id.theMonitorSwitch);

        if (true) {
            theWhackAMoleSwitch.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
                public void onCheckedChanged(android.widget.CompoundButton buttonView, boolean isChecked) {
                    System.out.println("            in theWhackAMoleSwitch onCheckedChanged(isChecked=" + isChecked + ")");
                    TheService.mStaticWhackAMole = isChecked;
                    if (isChecked) {
                        // TODO: do this through the service somehow?
                        try {
                          Settings.System.putInt(getContentResolver(), Settings.System.ACCELEROMETER_ROTATION, 0);
                        } catch (SecurityException e) {
                            // XXX dup code
                            System.out.println("          Oh no, can't set system settings-- were permissions revoked?");
                            android.widget.Toast.makeText(TheActivity.this, " Oh no, can't set system settings-- were permissions revoked?\nHere, please grant the permission.", android.widget.Toast.LENGTH_SHORT).show();
                            android.content.Intent grantIntent = new android.content.Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS);
                            grantIntent.setData(android.net.Uri.parse("package:"+getPackageName()));
                            System.out.println("              grantIntent = "+grantIntent);
                            System.out.println("              calling startActivity with ACTION_MANAGE_WRITE_SETTINGS");
                            startActivity(grantIntent);
                            System.out.println("              returned from startActivity with ACTION_MANAGE_WRITE_SETTINGS");
                        }
                    }
                    System.out.println("            out theWhackAMoleSwitch onCheckedChanged(isChecked=" + isChecked + ")");
                }
            });
        }
        if (true) {
            theAutoRotateSwitch.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
                public void onCheckedChanged(android.widget.CompoundButton buttonView, boolean isChecked) {
                    System.out.println("            in theAutoRotateSwitch onCheckedChanged(isChecked=" + isChecked + ")");
                    TheService.mStaticAutoRotate = isChecked;
                    if (isChecked) {
                        // TODO: do this through the service somehow?
                        // TODO: need to catch SecurityException!
                        Settings.System.putInt(getContentResolver(), Settings.System.ACCELEROMETER_ROTATION, 0);
                    }
                    // TODO: make it update immediately?
                    System.out.println("            out theAutoRotateSwitch onCheckedChanged(isChecked=" + isChecked + ")");
                }
            });
        }
        if (true) {
            theMonitorSwitch.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
                public void onCheckedChanged(android.widget.CompoundButton buttonView, boolean isChecked) {
                    System.out.println("            in theMonitorSwitch onCheckedChanged(isChecked=" + isChecked + ")");
                    mHandler.removeCallbacks(mRunnable); // ok if it wasn't scheduled
                    if (isChecked) {
                        // Presumably the activity is between onResume and onPause when this happens,
                        // so it's correct to start the periodic callback here.
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

    private void updateAccelerometerOrientationDegreesTextView() {
        android.widget.TextView theAccelerometerOrientationDegreesTextView = (android.widget.TextView)findViewById(R.id.theAccelerometerOrientationDegreesTextView); // XXX TODO: make this a member

        // Can I just query the current value of the accelerometer, without registering a changed-listener??

        // Q: how to get the android device's initial physical orientation when using OrientationEventListener?

        /*
        I'm using an OrientationEventListener to track the android device's physical orientation.
        But it seems I don't get any information about the orientation until the first onOrientationChanged event.
        If the device is lying still, that event might not happen for an arbitrarily long period of time.
        How do I determine the device's physical orientation prior to the first orientation change?
        Tags: android

        hmm, dup of: http://stackoverflow.com/questions/20131490/android-sensormanager-getorientation-before-event-triggered
        ah, it says take a look at getOrientationUsingGetRotationMatrix().

        Hmm that requires gravity and geomagnetic returned from sensorevents of sensors of type TYPE_ACCELEROMETER and TYPE_MAGNETIC_FIELD.
        And I have no idea how to use those.
        Do I just get one event of each and then unregister? or what?  this is frickin bizarre.
        Maybe look at source code for OrientationEventListener?
        http://alvinalexander.com/java/jwarehouse/android/core/java/android/view/OrientationEventListener.java.shtml
        Hmm, it's just translating ACCELEROMETER onSensorChanged events.  Hmm.
        */

        if (TheService.mStaticDegreesIsValid) {
            theAccelerometerOrientationDegreesTextView.setText("accelerometer degrees: "+TheService.mStaticDegrees);
        } else {
            theAccelerometerOrientationDegreesTextView.setText("accelerometer degrees: ???");
        }

        if (false) // XXX needs work, maybe just kill this
        {
            // http://stackoverflow.com/questions/20131490/android-sensormanager-getorientation-before-event-triggered#answer-20173273
            float R[] = new float[9];
            float I[] = new float[9];
            float gravity[] = new float[3]; // woops! need to initialize this using value returned by a SensorEvent of a Sensor of type TYPE_ACCELEROMETER
            float geomagnetic[] = new float[3]; // woops! need to initialize this using value returned by a SensorEvent of a Sensor of type TYPE_MAGNETIC_FIELD
            if (!android.hardware.SensorManager.getRotationMatrix(R,
                                                 I,
                                                 gravity,
                                                 geomagnetic)) {
                theAccelerometerOrientationDegreesTextView.setText("(SensorManager.getRotationMatrix failed)");
            } else {
                // https://developer.android.com/reference/android/hardware/SensorManager.html#jd-content
                float azimuthPitchRoll[] = new float[3];
                android.hardware.SensorManager.getOrientation(R, azimuthPitchRoll);
                theAccelerometerOrientationDegreesTextView.setText(
                  "R = "
                  +R[0]+" "+
                  +R[1]+" "+
                  +R[2]+" "+
                  +R[3]+" "+
                  +R[4]+" "+
                  +R[5]+" "+
                  +R[6]+" "+
                  +R[7]+" "+
                  +R[8]+" "+
                  "azimuth="+azimuthPitchRoll[0]+" pitch="+azimuthPitchRoll[1]+" roll="+azimuthPitchRoll[2]
                  );
            }
        }
    }

    private void updateAccelerometerRotationTextView() {
        android.widget.TextView theAccelerometerRotationTextView = (android.widget.TextView)findViewById(R.id.theAccelerometerRotationTextView); // XXX TODO: make this a member
        try {
            int accelerometerRotation = Settings.System.getInt(getContentResolver(), Settings.System.ACCELEROMETER_ROTATION);
            theAccelerometerRotationTextView.setText("  Settings.System.ACCELEROMETER_ROTATION: "+accelerometerRotation);

        } catch (Settings.SettingNotFoundException e) {
            theAccelerometerRotationTextView.setText("  Settings.System.ACCELEROMETER_ROTATION setting not found!?");
        }
    }
    private void updateUserRotationTextView() {
        android.widget.TextView theUserRotationTextView = (android.widget.TextView)findViewById(R.id.theUserRotationTextView); // XXX TODO: make this a member
        try {
            int userRotation = Settings.System.getInt(getContentResolver(), Settings.System.USER_ROTATION);
            theUserRotationTextView.setText("  Settings.System.USER_ROTATION: "+TheService.surfaceRotationConstantToString(userRotation));

        } catch (Settings.SettingNotFoundException e) {
            theUserRotationTextView.setText("  Settings.System.USER_ROTATION setting not found!?");
        }
    }
    private void updateConfigurationOrientationTextView() {
        android.widget.TextView theConfigurationOrientationTextView = (android.widget.TextView)findViewById(R.id.theConfigurationOrientationTextView); // XXX TODO: make this a member
        theConfigurationOrientationTextView.setText("  getResources().getConfiguration().orientation = " + TheService.orientationConstantToString(getResources().getConfiguration().orientation));
    }
    private void updatePolledStatusTextView() {
        android.widget.TextView thePolledStatusTextView = (android.widget.TextView)findViewById(R.id.thePolledStatusTextView); // XXX TODO: make this a member
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
        message += ("  getRequestedOrientation() = " + TheService.screenOrientationConstantToString(getRequestedOrientation()));
        message += "\n\n";
        message += (gotAccelerometerRotation ? "  Settings.System.ACCELEROMETER_ROTATION: "+accelerometerRotation : "[no Settings.System.ACCELEROMETER_ROTATION]");
        message += "\n\n";
        message += (gotUserRotation ? "  Settings.System.USER_ROTATION: "+TheService.surfaceRotationConstantToString(userRotation) : "[no Settings.System.USER_ROTATION]");
        message += "\n\n";
        message += ("  getResources().getConfiguration().orientation = " + TheService.orientationConstantToString(getResources().getConfiguration().orientation));
        message += "\n\n";
        message += ("  getWindowManager().getDefaultDisplay().getRotation() = " + TheService.surfaceRotationConstantToString(getWindowManager().getDefaultDisplay().getRotation()));
        message += "\n\n";
        mNumUpdates++;
        message += "  "+mNumUpdates+" update"+(mNumUpdates==1?"":"s");
        thePolledStatusTextView.setText(message);
    }

    @Override
    protected void onStart() {
        System.out.println("        in onStart");
        super.onStart();
        android.widget.Switch theServiceSwitch = (android.widget.Switch)findViewById(R.id.theServiceSwitch);

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

        {
            android.support.v4.content.LocalBroadcastManager.getInstance(this).registerReceiver(mBroadcastReceiver, new android.content.IntentFilter("degrees changed"));
        }

        {
            android.net.Uri uri = Settings.System.getUriFor(Settings.System.ACCELEROMETER_ROTATION);
            System.out.println("      uri = "+uri);
            getContentResolver().registerContentObserver(uri, false, mAccelerometerRotationObserver);
        }

        {
            android.net.Uri uri = Settings.System.getUriFor(Settings.System.USER_ROTATION);
            System.out.println("      uri = "+uri);
            getContentResolver().registerContentObserver(uri, false, mUserRotationObserver);
        }

        {
          android.widget.Switch theMonitorSwitch = (android.widget.Switch)findViewById(R.id.theMonitorSwitch);
          if (theMonitorSwitch.isChecked()) {
              mHandler.postDelayed(mRunnable, 1*1000);
          }
        }

        updateAccelerometerOrientationDegreesTextView();
        updateAccelerometerRotationTextView();
        updateUserRotationTextView();
        updateConfigurationOrientationTextView();
        updatePolledStatusTextView();

        System.out.println("            out onResume");
    }

    @Override
    protected void onPause() {
        System.out.println("            in onPause");

        android.support.v4.content.LocalBroadcastManager.getInstance(this).unregisterReceiver(mBroadcastReceiver);

        getContentResolver().unregisterContentObserver(mAccelerometerRotationObserver); // ok if it wasn't registered, I think... although it should be
        getContentResolver().unregisterContentObserver(mUserRotationObserver); // ok if it wasn't registered, I think... although it should be
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
        System.out.println("  newConfig = "+newConfig);
        System.out.println("  newConfig.orientation = "+TheService.orientationConstantToString(newConfig.orientation));
        //setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        updateConfigurationOrientationTextView();
        System.out.println("out onConfigurationChanged");
    }
}
