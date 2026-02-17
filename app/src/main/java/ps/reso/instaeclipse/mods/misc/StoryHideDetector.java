package ps.reso.instaeclipse.mods.misc;

import android.app.AndroidAppHelper;
import android.content.Context;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.toast.CustomToast;

/**
 * Detects if a user has hidden their story from you or muted/blocked your stories.
 * 
 * This works by hooking into the FriendshipStatus class and checking:
 * - hide_story: Whether the user hid their story from you
 * - is_muting_reel: Whether the user muted your stories (reels)
 * - is_blocking_reel: Whether the user blocked your stories (reels)
 * 
 * These fields are part of the friendship status returned when viewing a user's profile.
 */
public class StoryHideDetector {

    private static final String TAG = "(InstaEclipse | StoryHideDetector)";
    
    // Track which users we've already shown a toast for (to avoid duplicates)
    private static final Set<String> shownUsers = new HashSet<>();

    /**
     * Find and hook the FriendshipStatus methods that indicate story hiding.
     */
    public void findAndHookStoryHideMethods(DexKitBridge bridge, ClassLoader classLoader) {
        try {
            XposedBridge.log(TAG + " Starting story hide detection setup...");

            int hookedCount = 0;

            // Method 1: Find methods that use the "hide_story" string
            hookedCount += hookMethodsUsingString(bridge, classLoader, "hide_story", "🚫 This user hid their story from you");

            // Method 2: Find methods that use the "is_muting_reel" string
            hookedCount += hookMethodsUsingString(bridge, classLoader, "is_muting_reel", "🔇 This user muted your stories");

            // Method 3: Find methods that use the "is_blocking_reel" string
            hookedCount += hookMethodsUsingString(bridge, classLoader, "is_blocking_reel", "⛔ This user blocked your stories");

            // Method 4: Fallback - Hook Boolean methods in FriendshipStatusImpl that have hide/mute/block in name
            hookedCount += hookBooleanMethodsByName(bridge, classLoader);

            if (hookedCount > 0) {
                FeatureStatusTracker.setHooked("ShowStoryHideToast");
                XposedBridge.log(TAG + " ✅ Story hide detection enabled (" + hookedCount + " methods hooked)");
            } else {
                XposedBridge.log(TAG + " ⚠️ No story-hide related methods found to hook");
            }

        } catch (Throwable e) {
            XposedBridge.log(TAG + " ❌ Exception: " + e.getMessage());
        }
    }

    /**
     * Hook methods that use a specific string (like "hide_story", "is_muting_reel", etc.)
     */
    private int hookMethodsUsingString(DexKitBridge bridge, ClassLoader classLoader, String searchString, String toastMessage) {
        int count = 0;
        try {
            List<MethodData> methods = bridge.findMethod(FindMethod.create()
                    .matcher(MethodMatcher.create().usingStrings(searchString)));

            for (MethodData methodData : methods) {
                try {
                    // Only hook methods that return Boolean or boolean
                    String returnType = String.valueOf(methodData.getReturnType());
                    if (!returnType.contains("Boolean") && !returnType.contains("boolean")) {
                        continue;
                    }

                    Method reflectMethod = methodData.getMethodInstance(classLoader);
                    String methodName = methodData.getName();

                    XposedBridge.hookMethod(reflectMethod, createStoryHideHook(methodName, toastMessage));
                    count++;
                    XposedBridge.log(TAG + " Hooked method using '" + searchString + "': " + methodName);

                } catch (Throwable e) {
                    XposedBridge.log(TAG + " Failed to hook method using '" + searchString + "': " + e.getMessage());
                }
            }
        } catch (Throwable e) {
            XposedBridge.log(TAG + " Error finding methods using '" + searchString + "': " + e.getMessage());
        }
        return count;
    }

    /**
     * Hook Boolean methods in FriendshipStatusImpl that have hide/mute/block in their name
     */
    private int hookBooleanMethodsByName(DexKitBridge bridge, ClassLoader classLoader) {
        int count = 0;
        try {
            List<MethodData> booleanMethods = bridge.findMethod(FindMethod.create()
                    .matcher(MethodMatcher.create()
                            .declaredClass("com.instagram.user.model.FriendshipStatusImpl")
                            .returnType("java.lang.Boolean")));

            for (MethodData methodData : booleanMethods) {
                try {
                    String methodName = methodData.getName();
                    String methodLower = methodName.toLowerCase();

                    // Check if this method is related to story hiding
                    boolean isStoryHideRelated = (methodLower.contains("hide") && (methodLower.contains("story") || methodLower.contains("reel"))) ||
                                                  (methodLower.contains("mute") && (methodLower.contains("reel") || methodLower.contains("story"))) ||
                                                  (methodLower.contains("block") && (methodLower.contains("reel") || methodLower.contains("story")));

                    if (!isStoryHideRelated) {
                        continue;
                    }

                    Method reflectMethod = methodData.getMethodInstance(classLoader);

                    // Determine the toast message based on method name
                    String toastMessage;
                    if (methodLower.contains("hide")) {
                        toastMessage = "🚫 This user hid their story from you";
                    } else if (methodLower.contains("mute")) {
                        toastMessage = "🔇 This user muted your stories";
                    } else {
                        toastMessage = "⛔ This user blocked your stories";
                    }

                    XposedBridge.hookMethod(reflectMethod, createStoryHideHook(methodName, toastMessage));
                    count++;
                    XposedBridge.log(TAG + " Hooked Boolean method: " + methodName);

                } catch (Throwable e) {
                    XposedBridge.log(TAG + " Failed to hook Boolean method: " + e.getMessage());
                }
            }
        } catch (Throwable e) {
            XposedBridge.log(TAG + " Error finding Boolean methods: " + e.getMessage());
        }
        return count;
    }

    /**
     * Create a method hook for story hide detection
     */
    private XC_MethodHook createStoryHideHook(String methodName, String toastMessage) {
        return new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                try {
                    if (!FeatureFlags.showStoryHideToast) {
                        return;
                    }

                    Boolean result = (Boolean) param.getResult();
                    if (result == null || !result) {
                        return; // Only show toast if the value is true
                    }

                    // Get the current user ID being viewed
                    String targetId = ps.reso.instaeclipse.utils.tracker.FollowIndicatorTracker.currentlyViewedUserId;
                    if (targetId == null || targetId.isEmpty()) {
                        return;
                    }

                    // Avoid showing duplicate toasts for the same user
                    String key = targetId + "_" + methodName;
                    if (shownUsers.contains(key)) {
                        return;
                    }
                    shownUsers.add(key);

                    // Show the toast
                    Context context = AndroidAppHelper.currentApplication().getApplicationContext();
                    CustomToast.showCustomToast(context, toastMessage);
                    XposedBridge.log(TAG + " Detected: " + toastMessage + " (method: " + methodName + ", userId: " + targetId + ")");

                } catch (Throwable e) {
                    XposedBridge.log(TAG + " Error in hook callback: " + e.getMessage());
                }
            }
        };
    }

    /**
     * Clear the cache of shown users (call this when viewing a new profile)
     */
    public static void clearShownUsersCache() {
        shownUsers.clear();
    }
}