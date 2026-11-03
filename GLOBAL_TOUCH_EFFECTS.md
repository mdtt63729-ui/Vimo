# Global Touch Effects

## Scope

The project uses the Android View system and Material Components (not Jetpack Compose). A single application-level `ActivityLifecycleCallbacks` installer now adds consistent feedback to clickable and long-clickable Views across Activity windows.

## Behavior

- Adds a theme-aware Android `RippleDrawable` as a foreground when a clickable View does not already have a ripple background or a custom foreground.
- Uses a clone of the View's existing background as the ripple mask when possible, preserving rounded capsule/card silhouettes.
- Adds a restrained press scale (0.982) with a quick press-in and smooth release when the View does not already have a `StateListAnimator`.
- Keeps existing Material ripples, custom foregrounds, component backgrounds, click listeners, and custom state animators intact.
- Scans the initial View tree and newly inflated/recycled content on layout changes. Already styled Views are memoized to avoid repeatedly changing them.
- Ripple color resolves from the active theme's `colorControlHighlight`, so light and dark themes use their own theme values.

## Integration

`ATubeApp.onCreate()` installs `GlobalTouchEffects` once. No screen-specific click listener rewrites are required.

## Validation note

A Kotlin/Gradle compile was attempted, but the wrapper could not download Gradle 9.4.1 because the build environment had no network access (`UnknownHostException: services.gradle.org`). Source-level integration and archive integrity were checked; a successful Android build is not claimed.
