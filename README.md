# RotationLockAdaptiveShim

This is an imitation of the RotationLockAdaptive app, which doesn't seem to work on recent Android releases.
Or, maybe it does but:
  (1) it uses accessibility service, but not for accessibility, so will get slapped by play store eventually
  (2) requires turning off its obscure "Lock screen on portrait" setting, otherwise tapping on the confirmation icon does nothing.
      I don't quite understand what that's supposed to do, but its doc says:
        "Revert to portrait mode when lock screen is activated (recommended to enable this if the lock screen of your device does not function normally)"
