//
// BUG: dial rotation wrong initially
// BUG: why does there seem to be a delay after I click "yes" and before it rotates? seems more responsive when it's not prompting
// BUG: when turning *off* overlay and it's red, it flashes off-on-off
// BUG: if service is started while permissions aren't there (say, were revoked),
//       process crashes.  Need to catch this and do something better I think.
// BUG: if permission revoked in midstream and double-opt-in-dance is done,
//       if activity isn't up, and it's the first time,
//       the system settings screen is (sometimes) delayed until after the toast disappears!
// TODO: better communication from activity to service:
//         - when override toggled, should update the overlay immediately
//         - when service first turned on, should apply the overlay immediately even if already rotated properly
// TODO: better communication from service to activity:
//         - ui should monitor the overlay state: whether it's visible, and its getRequestedOrientation
// TODO: enhance ui look
//         - Put title at top of activity screen
//         - uncrowd ui
//         - put bottom stuff in "advanced" or "debug" or "devel" section, closed by default
//         - put current line ("up" if static dial, towards screen top if moving dial) and current effective quadrant bounds on dial, in red probably
//         - maybe option for different dial display style, where numbers stay with screen and only up vector moves?  (would have to reverse the numbers on the dial)
// TODO: enhance ui functionality
//         - toggle switch to turn on/off dial?
//         - make listened/polled values turn red when they change and then fade to black? hmm
//         - control verbosity levels
// TODO: actually make it work correctly when activity restarts due to orientation: remove the thing from the manifest? maybe worth a try
// TODO: if permission got revoked and we re-do the double-opt-in-dance, we end up not having written the value... I think? have to think about the consequences. maybe not too bad.
// TODO: ask question on stackoverflow about interaction between
//           setRequestedOrientation()
//           ACCELEROMETER_ROTATION
//           USER_ROTATION
//       The following may be useful:
//           https://vaneyckt.io/posts/programmatically_rotating_the_android_screen/
//       unfortunately, it can only control accelerometer_rotation and user_rotation.
//       Is there a way to query the windowmanager's stack of views?
// TODO: ask on stackoverflow whether there are any downsides
//       to communicating by calling methods on the singleton
//

package com.example.donhatch.rotationlockadaptiveshim;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.provider.Settings;
import android.util.Log;
import android.view.Surface;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.ImageView;
import android.widget.RelativeLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

public class TheActivity extends Activity {

    private static final String TAG = "RotationLockAdaptiveShim activity";

    private static void CHECK(boolean condition) {
        if (!condition) {
            throw new AssertionError("CHECK failed");
        }
    }

    // We set this temporarily while setting the checkbox from the program,
    // so that the onCheckedChanged listener can tell that's what happened.
    private boolean mSettingCheckedFromProgram = false;

    private long mNumUpdates = 0;
    private boolean mPolling = false;  // TODO: make this a shared preference so it will persist?
    private int mMostRecentConfigurationOrientation = -1;  // from most recent onConfigurationChanged, or getResources().getConfiguration().orientation initially

    public static TheActivity theRunningActivity = null; // actually not sure there is guaranteed to be at most one

    private Handler mPollingHandler = new Handler();
    private Runnable mPollingRunnable = new Runnable() {
        @Override
        public void run() {
            Log.i(TAG, "                in once-per-second poll (this should only happen when ui is visible)");
            updateAccelerometerOrientationDegreesTextView();
            updatePolledStatusTextView();
            mPollingHandler.postDelayed(this, 1*1000);
            Log.i(TAG, "                out once-per-second poll (this should only happen when ui is visible)");
        };
    };

    private BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getAction().equals("degrees changed")) {
                //Log.i(TAG, "                in onReceive: "+intent.getAction());
                int oldDegrees = intent.getIntExtra("old degrees", -100);
                int newDegrees = intent.getIntExtra("new degrees", -100);
                double oldDegreesSmoothed = intent.getDoubleExtra("old degrees smoothed", -100.);
                double newDegreesSmoothed = intent.getDoubleExtra("new degrees smoothed", -100.);
                //Log.i(TAG, "                  intent.getDoubleExtra(\"old degrees\") = "+oldDegrees);
                //Log.i(TAG, "                  intent.getDoubleExtra(\"new degrees\") = "+newDegrees);
                //Log.i(TAG, "                  intent.getDoubleExtra(\"old degrees smoothed\") = "+oldDegreesSmoothed);
                //Log.i(TAG, "                  intent.getDoubleExtra(\"new degrees smoothed\") = "+newDegreesSmoothed);
                TextView theAccelerometerOrientationDegreesTextView = (TextView)findViewById(R.id.theAccelerometerOrientationDegreesTextView);
                theAccelerometerOrientationDegreesTextView.setText("  accelerometer degrees (most recent update): "+oldDegrees+" -> "+newDegrees);

                if (true) {
                    ImageView theDialImageView = (ImageView)findViewById(R.id.theDialImageView);

                    double newDialRotation = 90. - newDegreesSmoothed;

                    int currentDisplayRotation = getWindowManager().getDefaultDisplay().getRotation();
                    // sign determined by dog science
                    switch (currentDisplayRotation) {
                        case Surface.ROTATION_0: newDialRotation -= 0; break;
                        case Surface.ROTATION_90: newDialRotation -= 90; break;
                        case Surface.ROTATION_180: newDialRotation -= 180; break;
                        case Surface.ROTATION_270: newDialRotation -= 270; break;
                        default: CHECK(false);
                    }

                    theDialImageView.setRotation((float)newDialRotation);

                    // XXX not sure what I want here
                    theDialImageView.setScaleX(1.f);
                    theDialImageView.setScaleY(1.f);
                }

                //Log.i(TAG, "                out onReceive: "+intent.getAction());
            } else if (intent.getAction().equals("mStaticClosestCompassPoint changed")) {
                Log.i(TAG, "                in onReceive: "+intent.getAction());
                int oldClosestCompassPoint = intent.getIntExtra("old mStaticClosestCompassPoint", -100);
                int newClosestCompassPoint = intent.getIntExtra("new mStaticClosestCompassPoint", -100);
                Log.i(TAG, "                  intent.getIntExtra(\"old mStaticClosestCompassPoint\") = "+oldClosestCompassPoint);
                Log.i(TAG, "                  intent.getIntExtra(\"new mStaticClosestCompassPoint\") = "+newClosestCompassPoint);
                /* (it's commented out in the layout too, made things too crowded)
                TextView theClosestCompassPointTextView = (TextView)findViewById(R.id.theClosestCompassPointTextView);
                theClosestCompassPointTextView.setText("  TheService.mStaticClosestCompassPoint: "+oldClosestCompassPoint+" -> "+newClosestCompassPoint);
                */
                Log.i(TAG, "                out onReceive: "+intent.getAction());
            } else if (intent.getAction().equals("mStaticPromptFirst changed")) {
                Log.i(TAG, "                in onReceive: "+intent.getAction());
                boolean newStaticPromptFirst = intent.getBooleanExtra("new mStaticPromptFirst", true);
                Log.i(TAG, "                  setting thePromptFirstSwitch.setChecked("+newStaticPromptFirst+")");
                Switch thePromptFirstSwitch = (Switch)findViewById(R.id.thePromptFirstSwitch);
                thePromptFirstSwitch.setChecked(newStaticPromptFirst);
                Log.i(TAG, "                out onReceive: "+intent.getAction());
            } else {
                Log.i(TAG, "                in onReceive: "+intent.getAction());
                Log.i(TAG, "                  (unrecognized)");
                CHECK(false);  // shouldn't happen
                Log.i(TAG, "                out onReceive: "+intent.getAction());
            }
        }
    };  // mBroadcastReceiver

    private ContentObserver mAccelerometerRotationObserver = new ContentObserver(new Handler()) {
        // Per https://developer.android.com/reference/android/database/ContentObserver.html :
        // Delegate to ensure correct operation on older versions of the framework
        // that didn't have the onChange(boolean, Uri) method. (XXX do I need to worry about this? is framework runtime, or my compiletime?)
        @Override
        public void onChange(boolean selfChange) {
            Log.i(TAG, "            in TheActivity mAccelerometerRotationObserver onChange(selfChange="+selfChange+")");
            onChange(selfChange, null);
            Log.i(TAG, "            out TheActivity mAccelerometerRotationObserver onChange(selfChange="+selfChange+")");
        }
        @Override
        public void onChange(boolean selfChange, Uri uri) {
            Log.i(TAG, "            in TheActivity mAccelerometerRotationObserver onChange(selfChange="+selfChange+", uri="+uri+")");
            try {
                Log.i(TAG, "              Settings.System.ACCELEROMETER_ROTATION: "+Settings.System.getInt(getContentResolver(), Settings.System.ACCELEROMETER_ROTATION));
            } catch (Settings.SettingNotFoundException e) {
                Log.i(TAG, "              Settings.System.ACCELEROMETER_ROTATION setting not found!?");
            }
            try {
                Log.i(TAG, "              (Settings.System.USER_ROTATION: "+TheService.surfaceRotationConstantToString(Settings.System.getInt(getContentResolver(), Settings.System.USER_ROTATION))+")");

            } catch (Settings.SettingNotFoundException e) {
                Log.i(TAG, "              (Settings.System.USER_ROTATION setting not found!?)");
            }
            updateAccelerometerRotationTextView();
            Log.i(TAG, "            out TheActivity mAccelerometerRotationObserver onChange(selfChange="+selfChange+", uri="+uri+")");
        }
    };  // mAccelerometerRotationObserver
    ContentObserver mUserRotationObserver = new ContentObserver(new Handler()) {
        // Per https://developer.android.com/reference/android/database/ContentObserver.html :
        // Delegate to ensure correct operation on older versions of the framework
        // that didn't have the onChange(boolean, Uri) method. (XXX do I need to worry about this? is framework runtime, or my compiletime?)
        @Override
        public void onChange(boolean selfChange) {
            Log.i(TAG, "            in TheActivity mUserRotationObserver onChange(selfChange="+selfChange+")");
            onChange(selfChange, null);
            Log.i(TAG, "            out TheActivity mUserRotationObserver onChange(selfChange="+selfChange+")");
        }
        @Override
        public void onChange(boolean selfChange, Uri uri) {
            Log.i(TAG, "            in TheActivity mUserRotationObserver onChange(selfChange="+selfChange+", uri="+uri+")");
            try {
                Log.i(TAG, "              (Settings.System.ACCELEROMETER_ROTATION: "+Settings.System.getInt(getContentResolver(), Settings.System.ACCELEROMETER_ROTATION)+")");
            } catch (Settings.SettingNotFoundException e) {
                Log.i(TAG, "              (Settings.System.ACCELEROMETER_ROTATION setting not found!?)");
            }
            try {
                Log.i(TAG, "              Settings.System.USER_ROTATION: "+TheService.surfaceRotationConstantToString(Settings.System.getInt(getContentResolver(), Settings.System.USER_ROTATION)));

            } catch (Settings.SettingNotFoundException e) {
                Log.i(TAG, "              Settings.System.USER_ROTATION setting not found!?");
            }
            updateUserRotationTextView();
            Log.i(TAG, "            out TheActivity mUserRotationObserver onChange(selfChange="+selfChange+", uri="+uri+")");
        }
    };  // mUserRotationObserver

    public TheActivity() {
        Log.i(TAG, " TheActivity ctor");
        Log.i(TAG, " TheActivity ctor");
    }


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Log.i(TAG, "    in onCreate");
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        // TODO: members
        Switch theServiceSwitch = (Switch)findViewById(R.id.theServiceSwitch);
        Button theAppSettingsButton = (Button)findViewById(R.id.theAppSettingsButton);
        Switch theWhackAMoleSwitch = (Switch)findViewById(R.id.theWhackAMoleSwitch);
        Switch theAutoRotateSwitch = (Switch)findViewById(R.id.theAutoRotateSwitch);
        Switch thePromptFirstSwitch = (Switch)findViewById(R.id.thePromptFirstSwitch);
        Switch theOverrideSwitch = (Switch)findViewById(R.id.theOverrideSwitch);
        Switch theRedSwitch = (Switch)findViewById(R.id.theRedSwitch);
        ImageView theDialImageView = (ImageView)findViewById(R.id.theDialImageView);
        Switch theMonitorSwitch = (Switch)findViewById(R.id.theMonitorSwitch);
        TextView thePolledValuesHeaderTextView = (TextView)findViewById(R.id.thePolledValuesHeaderTextView);
        TextView thePolledStatusTextView = (TextView)findViewById(R.id.thePolledStatusTextView);

        theWhackAMoleSwitch.setChecked(TheService.mStaticWhackAMole);
        theAutoRotateSwitch.setChecked(TheService.mStaticAutoRotate);
        thePromptFirstSwitch.setChecked(TheService.mStaticPromptFirst);
        theOverrideSwitch.setChecked(TheService.mStaticOverride);
        theRedSwitch.setChecked(TheService.getRed());
        theMonitorSwitch.setChecked(mPolling);
        thePolledValuesHeaderTextView.setEnabled(mPolling);
        thePolledStatusTextView.setEnabled(mPolling);

        if (true) {
            theWhackAMoleSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                    Log.i(TAG, "            in theWhackAMoleSwitch onCheckedChanged(isChecked=" + isChecked + ")");
                    TheService.mStaticWhackAMole = isChecked;
                    if (isChecked) {
                        // TODO: do this through the service somehow?
                        try {
                          Settings.System.putInt(getContentResolver(), Settings.System.ACCELEROMETER_ROTATION, 0);
                        } catch (SecurityException e) {
                            // XXX dup code
                            Log.i(TAG, "          Oh no, can't set system settings-- were permissions revoked?");
                            Toast.makeText(TheActivity.this, " Oh no, can't set system settings-- were permissions revoked?\nHere, please grant the permission.", Toast.LENGTH_SHORT).show();
                            Intent grantIntent = new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS);
                            grantIntent.setData(Uri.parse("package:"+getPackageName()));
                            Log.i(TAG, "              grantIntent = "+grantIntent);
                            Log.i(TAG, "              calling startActivity with ACTION_MANAGE_WRITE_SETTINGS");
                            startActivity(grantIntent);
                            Log.i(TAG, "              returned from startActivity with ACTION_MANAGE_WRITE_SETTINGS");
                        }
                    }
                    Log.i(TAG, "            out theWhackAMoleSwitch onCheckedChanged(isChecked=" + isChecked + ")");
                }
            });
        }
        if (true) {
            // I had a "force stop" button, but it's almost as easy to just let the user get to it through the "app settings" button, it's 2 clicks.  Force stop is probably hard to implement.
            // http://android.stackexchange.com/questions/33801/what-does-the-force-stop-button-mean#answer-48167
            theAppSettingsButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    Log.i(TAG, "            in theAppSettingsButton onClick");
                    startActivityForResult(
                        new Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:"+getPackageName())),
                        0);
                    Log.i(TAG, "            out theAppSettingsButton onClick");
                }
            });
        }
        if (true) {
            theAutoRotateSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                    Log.i(TAG, "            in theAutoRotateSwitch onCheckedChanged(isChecked=" + isChecked + ")");
                    TheService.mStaticAutoRotate = isChecked;
                    if (isChecked) {
                        // TODO: do this through the service somehow?
                        // TODO: need to catch SecurityException!
                        Settings.System.putInt(getContentResolver(), Settings.System.ACCELEROMETER_ROTATION, 0);
                        // XXX just nuke everything without thinking too much.  TODO: think about it better
                        TheService.mStaticDegreesIsValid = false;
                        TheService.mStaticClosestCompassPoint = -1;
                    }
                    // TODO: make it update immediately?
                    Log.i(TAG, "            out theAutoRotateSwitch onCheckedChanged(isChecked=" + isChecked + ")");
                }
            });
        }
        if (true) {
            thePromptFirstSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                    Log.i(TAG, "            in thePromptFirstSwitch onCheckedChanged(isChecked=" + isChecked + ")");
                    TheService.mStaticPromptFirst = isChecked;
                    // No immediate effect; this setting just modifies the behavior of autorotate
                    Log.i(TAG, "            out thePromptFirstSwitch onCheckedChanged(isChecked=" + isChecked + ")");
                }
            });
        }
        if (true) {
            theOverrideSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                    Log.i(TAG, "            in theOverrideSwitch onCheckedChanged(isChecked=" + isChecked + ")");
                    TheService.mStaticOverride = isChecked;
                    // No immediate effect; this setting just modifies the behavior of autorotate
                    // XXX TODO: but it should have immediate effect
                    Log.i(TAG, "            out theOverrideSwitch onCheckedChanged(isChecked=" + isChecked + ")");
                }
            });
        }
        if (true) {
            theRedSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                    Log.i(TAG, "            in theRedSwitch onCheckedChanged(isChecked=" + isChecked + ")");
                    TheService.setRed(isChecked);
                    Log.i(TAG, "            out theRedSwitch onCheckedChanged(isChecked=" + isChecked + ")");
                }
            });
        }
        if (true) {
            theMonitorSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                    Log.i(TAG, "            in theMonitorSwitch onCheckedChanged(isChecked=" + isChecked + ")");
                    mPolling = isChecked;
                    TextView thePolledValuesHeaderTextView = (TextView)findViewById(R.id.thePolledValuesHeaderTextView);
                    TextView thePolledStatusTextView = (TextView)findViewById(R.id.thePolledStatusTextView);
                    thePolledValuesHeaderTextView.setEnabled(mPolling);
                    thePolledStatusTextView.setEnabled(mPolling);
                    mPollingHandler.removeCallbacks(mPollingRunnable);  // ok if it wasn't scheduled
                    if (isChecked) {
                        // Presumably the activity is between onResume and onPause when this happens,
                        // so it's correct to start the periodic callback here.
                        mPollingHandler.postDelayed(mPollingRunnable, 0);  // immediately
                    }
                    Log.i(TAG, "            out theMonitorSwitch onCheckedChanged(isChecked=" + isChecked + ")");
                }
            });
        }
        if (true) {
            theServiceSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                    Log.i(TAG, "            in theServiceSwitch onCheckedChanged(isChecked=" + isChecked + ")");
                    if (mSettingCheckedFromProgram) {
                        Log.i(TAG, "              (from program; not doing anything)");
                    } else {
                        if (isChecked) {
                            Log.i(TAG, "              calling startService");
                            startService(new Intent(TheActivity.this, TheService.class));
                            Log.i(TAG, "              returned from startService");
                            if (false) {
                                // Make sure we can't mess up service's notion of whether it's running,
                                // by sending a whole flurry of stuff.
                                // NOTE: this does seem to confuse the notification icon! or, it did, before I started checking for mSettingCheckedFromProgram
                                startService(new Intent(TheActivity.this, TheService.class));
                                stopService(new Intent(TheActivity.this, TheService.class));
                                stopService(new Intent(TheActivity.this, TheService.class));
                                startService(new Intent(TheActivity.this, TheService.class));
                                startService(new Intent(TheActivity.this, TheService.class));
                            }
                            Log.i(TAG, "              setting text to \"Service is on  \"");
                            buttonView.setText("Service is on  "); // we assume startService is reliable
                        } else {
                            Log.i(TAG, "              calling stopService");
                            stopService(new Intent(TheActivity.this, TheService.class));
                            Log.i(TAG, "              returned from stopService");
                            if (false) {
                                // Make sure we can't mess up service's notion of whether it's running,
                                // by sending a whole flurry of stuff
                                // NOTE: this does seem to confuse the notification icon!
                                stopService(new Intent(TheActivity.this, TheService.class));
                                startService(new Intent(TheActivity.this, TheService.class));
                                startService(new Intent(TheActivity.this, TheService.class));
                                stopService(new Intent(TheActivity.this, TheService.class));
                                stopService(new Intent(TheActivity.this, TheService.class));
                            }
                            Log.i(TAG, "              setting text to \"Service is off \"");
                            buttonView.setText("Service is off "); // we assume stopService is reliable
                        }
                    }
                    Log.i(TAG, "            out theServiceSwitch onCheckedChanged(isChecked=" + isChecked + ")");
                }
            });
        }
        // Double-opt-in dance needed to write settings.
        // It causes the appropriate permissions screen to come up if it's wrong.
        // (Can also manually grant/ungrant by Settings -> Apps -> <this app> -> Modify system settings -> Yes/No, *if* activity is not running. (Force Stop first if it is))
        // Actually I think I can grant/ungrant on the fly, but the Settings switch gets out of sync with what it really is.  This is a reported bug, I think.
        if (!android.provider.Settings.System.canWrite(this)) {
            Intent grantIntent = new Intent(android.provider.Settings.ACTION_MANAGE_WRITE_SETTINGS, android.net.Uri.parse("package:"+getPackageName()));
            Log.i(TAG, "              grantIntent = "+grantIntent);
            Log.i(TAG, "              calling startActivity with ACTION_MANAGE_WRITE_SETTINGS");
            startActivity(grantIntent);
            Log.i(TAG, "              returned from startActivity with ACTION_MANAGE_WRITE_SETTINGS");
        } else {
            Log.i(TAG, "              can already modify system settings.  Cool.");
        }

        if (!android.provider.Settings.canDrawOverlays(this)) {
            Intent grantIntent = new Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:"+getPackageName()));
            Log.i(TAG, "              grantIntent = "+grantIntent);
            Log.i(TAG, "              calling startActivity with ACTION_MANAGE_OVERLAY_PERMISSION");
            startActivity(grantIntent); // XXX that example uses startActivityForResult. maybe use that when needed on the fly?
            Log.i(TAG, "              returned from startActivity with ACTION_MANAGE_OVERLAY_PERMISSION");
        } else {
            Log.i(TAG, "              can already draw overlays.  Cool.");
        }

        CHECK(theRunningActivity == null); // XXX not confident in this
        theRunningActivity = this;

        Log.i(TAG, "    out onCreate");
    }

    private void updateAccelerometerOrientationDegreesTextView() {
        TextView theAccelerometerOrientationDegreesTextView = (TextView)findViewById(R.id.theAccelerometerOrientationDegreesTextView);

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

        I think there's a good example of how to really use these things here, if I want to revisit this:
          http://stackoverflow.com/questions/4819626/android-phone-orientation-overview-including-compass?rq=1#answer-6804786
        */

        if (false) { // XXX need to unconfuse what I'm doing here. does this have any value?
            if (TheService.mStaticDegreesIsValid) {
                theAccelerometerOrientationDegreesTextView.setText("  accelerometer degrees: "+TheService.mStaticDegrees);
            } else {
                theAccelerometerOrientationDegreesTextView.setText("  accelerometer degrees: ???");
            }
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
        TextView theAccelerometerRotationTextView = (TextView)findViewById(R.id.theAccelerometerRotationTextView);
        try {
            int accelerometerRotation = Settings.System.getInt(getContentResolver(), Settings.System.ACCELEROMETER_ROTATION);
            theAccelerometerRotationTextView.setText("  Settings.System.ACCELEROMETER_ROTATION: "+accelerometerRotation);

        } catch (Settings.SettingNotFoundException e) {
            theAccelerometerRotationTextView.setText("  Settings.System.ACCELEROMETER_ROTATION setting not found!?");
        }
    }
    private void updateUserRotationTextView() {
        TextView theUserRotationTextView = (TextView)findViewById(R.id.theUserRotationTextView);
        try {
            int userRotation = Settings.System.getInt(getContentResolver(), Settings.System.USER_ROTATION);
            theUserRotationTextView.setText("  Settings.System.USER_ROTATION: "+TheService.surfaceRotationConstantToString(userRotation));

        } catch (Settings.SettingNotFoundException e) {
            theUserRotationTextView.setText("  Settings.System.USER_ROTATION setting not found!?");
        }
    }

    // set the text view to mMostRecentConfigurationOrientation.
    private void updateConfigurationOrientationTextView() {
        TextView theConfigurationOrientationTextView = (TextView)findViewById(R.id.theConfigurationOrientationTextView);
        // XXX this is a lie the first time-- maybe make that a parameter
        theConfigurationOrientationTextView.setText("  onConfigurationChanged newConfig.orientation = " + TheService.orientationConstantToString(mMostRecentConfigurationOrientation));
    }

    private void updatePolledStatusTextView() {
        TextView thePolledStatusTextView = (TextView)findViewById(R.id.thePolledStatusTextView);
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
        message += "\n";
        message += (gotAccelerometerRotation ? "  Settings.System.ACCELEROMETER_ROTATION: "+accelerometerRotation : "[no Settings.System.ACCELEROMETER_ROTATION]");
        message += "\n";
        message += (gotUserRotation ? "  Settings.System.USER_ROTATION: "+TheService.surfaceRotationConstantToString(userRotation) : "[no Settings.System.USER_ROTATION]");
        message += "\n";
        // XXX assert it's equal to mMostRecentConfigurationOrientation?  not sure, they might be slightly out of sync temporarily
        message += ("  getResources().getConfiguration().orientation = " + TheService.orientationConstantToString(getResources().getConfiguration().orientation));
        message += "\n";
        message += ("  getWindowManager().getDefaultDisplay().getRotation() = " + TheService.surfaceRotationConstantToString(getWindowManager().getDefaultDisplay().getRotation()));
        thePolledStatusTextView.setText(message);

        TextView thePolledValuesHeader = (TextView)findViewById(R.id.thePolledValuesHeaderTextView);
        mNumUpdates++;
        thePolledValuesHeader.setText("Polled values:  ("+mNumUpdates+" update"+(mNumUpdates==1?"":"s")+")");
    }

    @Override
    protected void onStart() {
        Log.i(TAG, "        in onStart");
        super.onStart();
        Switch theServiceSwitch = (Switch)findViewById(R.id.theServiceSwitch);

        boolean serviceIsRunning = TheService.theRunningService != null;
        Log.i(TAG, "          calling theServiceSwitch.setChecked("+serviceIsRunning+")");
        mSettingCheckedFromProgram = true;
        theServiceSwitch.setChecked(serviceIsRunning);
        mSettingCheckedFromProgram = false;
        Log.i(TAG, "          returned from theServiceSwitch.setChecked("+serviceIsRunning+")");
        // That invoked the listener which set the label to "Service is on" or "Service is off";
        // overwrite it with something that says "initially".
        if (serviceIsRunning) {
            Log.i(TAG, "          setting text to \"Service is initially on  \"");
            theServiceSwitch.setText("Service is initially on  ");
        } else {
            Log.i(TAG, "          setting text to \"Service is initially off \"");
            theServiceSwitch.setText("Service is initially off ");
        }
        Log.i(TAG, "        out onStart");
    }

    @Override
    protected void onResume() {
        Log.i(TAG, "            in onResume");
        super.onResume();

        {
            android.support.v4.content.LocalBroadcastManager.getInstance(this).registerReceiver(mBroadcastReceiver, new IntentFilter("degrees changed") {{
                addAction("mStaticPromptFirst changed");
                addAction("mStaticClosestCompassPoint changed");
            }});
        }

        {
            android.net.Uri uri = Settings.System.getUriFor(Settings.System.ACCELEROMETER_ROTATION);
            Log.i(TAG, "      uri = "+uri);
            getContentResolver().registerContentObserver(uri, false, mAccelerometerRotationObserver);
        }

        {
            android.net.Uri uri = Settings.System.getUriFor(Settings.System.USER_ROTATION);
            Log.i(TAG, "      uri = "+uri);
            getContentResolver().registerContentObserver(uri, false, mUserRotationObserver);
        }

        {
          if (mPolling) {
              mPollingHandler.postDelayed(mPollingRunnable, 0);  // immediately
          }
        }

        mMostRecentConfigurationOrientation = getResources().getConfiguration().orientation;

        updateAccelerometerOrientationDegreesTextView();
        updateAccelerometerRotationTextView();
        updateUserRotationTextView();
        updateConfigurationOrientationTextView();
        updatePolledStatusTextView();
        Log.i(TAG, "            out onResume");
    }

    @Override
    protected void onPause() {
        Log.i(TAG, "            in onPause");

        android.support.v4.content.LocalBroadcastManager.getInstance(this).unregisterReceiver(mBroadcastReceiver);

        getContentResolver().unregisterContentObserver(mAccelerometerRotationObserver); // ok if it wasn't registered, I think... although it should be
        getContentResolver().unregisterContentObserver(mUserRotationObserver); // ok if it wasn't registered, I think... although it should be
        mPollingHandler.removeCallbacks(mPollingRunnable); // ok if it wasn't scheduled

        super.onPause();
        Log.i(TAG, "            out onPause");
    }

    @Override
    protected void onStop() {
        Log.i(TAG, "        in onStop");
        super.onStop();
        Log.i(TAG, "        out onStop");
    }

    @Override
    protected void onDestroy() {
        Log.i(TAG, "    in onDestroy");
        super.onDestroy();
        if (theRunningActivity == this) theRunningActivity = null;
        Log.i(TAG, "    out onDestroy");
    }

    // Note, in order for this to be called (rather than the system stopping and restarting
    // the activity), the manifest must have "orientation|screenSize" in android:configChanges
    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        Log.i(TAG, " onConfigurationChanged");
        super.onConfigurationChanged(newConfig);
        Log.i(TAG, "  newConfig = "+newConfig);
        Log.i(TAG, "  newConfig.orientation = "+TheService.orientationConstantToString(newConfig.orientation));

        mMostRecentConfigurationOrientation = newConfig.orientation;
        updateConfigurationOrientationTextView();

        {
            Switch theOverrideSwitch = (Switch)findViewById(R.id.theOverrideSwitch);
            Switch theRedSwitch = (Switch)findViewById(R.id.theRedSwitch);
            RelativeLayout.LayoutParams redSwitchLayoutParams = ((RelativeLayout.LayoutParams)theRedSwitch.getLayoutParams());
            if (newConfig.orientation == Configuration.ORIENTATION_PORTRAIT) {
                // put red switch below override switch, right-aligned with it
                Log.i(TAG, "  putting red switch below and right-aligned with override switch");
                redSwitchLayoutParams.removeRule(RelativeLayout.RIGHT_OF);
                redSwitchLayoutParams.removeRule(RelativeLayout.ALIGN_TOP);
                redSwitchLayoutParams.addRule(RelativeLayout.BELOW, theOverrideSwitch.getId());
                redSwitchLayoutParams.addRule(RelativeLayout.ALIGN_RIGHT, theOverrideSwitch.getId());
                //layout_below = theOverrideSwitch;
                //theRedSwitch.alignRight = theOverrideSwitch;
            } else if (newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE) {
                // put red switch to right of override switch, top-aligned with it
                //theRedSwitch.layout_toRightOf = theOverrideSwitch;
                //theRedSwitch.alignTop = theOverrideSwitch;
                Log.i(TAG, "  putting red switch to right and top-aligned with override switch");
                redSwitchLayoutParams.removeRule(RelativeLayout.BELOW);
                redSwitchLayoutParams.removeRule(RelativeLayout.ALIGN_RIGHT);
                redSwitchLayoutParams.addRule(RelativeLayout.RIGHT_OF, theOverrideSwitch.getId());
                redSwitchLayoutParams.addRule(RelativeLayout.ALIGN_TOP, theOverrideSwitch.getId());
            }
        }

        Log.i(TAG, " onConfigurationChanged");
    }
}
