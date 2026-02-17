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
 * This works by hooking into the FriendshipStatus class and checking specific Boolean methods.
 * Based on APK analysis of Instagram 418.0.0.0.11:
 * - FriendshipStatus interface has multiple Boolean methods in a specific order
 * - The methods correspond to fields like: is_following, is_followed_by, is_muting_reel, etc.
 * 
 * Similar to FollowerIndicator, we use index-based access to find the correct methods.
 * 
 * Expected method order (based on Instagram's FriendshipStatus):
 * Index 0: is_following (whether you follow them)
 * Index 1: is_followed_by (whether they follow you) - used by FollowerIndicator
 * Index 2+: other status fields (blocking, muting, etc.)
 * 
 * The exact indices for hide_story, is_muting_reel, is_blocking_reel need to be determined
 * by checking the logs when the module runs.
 */
public class StoryHideDetector {

    private static final String TAG = "(InstaEclipse | StoryHideDetector)";
    
    // Track which users we've already shown a toast for (to avoid duplicates)
    private static final Set<String> shownUsers = new HashSet<>();

    /**
     * Find and hook the FriendshipStatus methods that indicate story hiding.
     * 
     * Approach: Similar to FollowerIndicator, we find Boolean methods in FriendshipStatus
     * interface and log all method names. Then we try to identify the correct ones.
     */
    public void findAndHookStoryHideMethods(DexKitBridge bridge, ClassLoader classLoader) {
        try {
            XposedBridge.log(TAG + " Starting story hide detection setup...");

            // Find all Boolean methods in FriendshipStatus interface
            List<MethodData> friendshipMethods = bridge.findMethod(FindMethod.create()
                    .matcher(MethodMatcher.create()
                            .declaredClass("com.instagram.user.model.FriendshipStatus")
                            .returnType("java.lang.Boolean")));

            if (friendshipMethods.isEmpty()) {
                XposedBridge.log(TAG + " ❌ No Boolean methods found in FriendshipStatus");
                return;
            }

            XposedBridge.log(TAG + " Found " + friendshipMethods.size() + " Boolean methods in FriendshipStatus");
            
            // Log all method names for debugging - THIS IS IMPORTANT FOR FINDING CORRECT INDICES
            StringBuilder methodList = new StringBuilder();
            for (int i = 0; i < friendshipMethods.size(); i++) {
                String methodName = friendshipMethods.get(i).getName();
                methodList.append("[").append(i).append(":").append(methodName).append("] ");
            }
            XposedBridge.log(TAG + " Methods: " + methodList.toString());

            int hookedCount = 0;

            // Strategy 1: Try to find methods by name pattern (for non-obfuscated or partially obfuscated)
            for (int i = 0; i < friendshipMethods.size(); i++) {
                MethodData methodData = friendshipMethods.get(i);
                String methodName = methodData.getName();
                String methodLower = methodName.toLowerCase();

                // Check for hide_story related method
                if (methodLower.contains("hidestory") || methodLower.contains("hide_story") || 
                    (methodLower.contains("hide") && methodLower.contains("story"))) {
                    try {
                        Method reflectMethod = methodData.getMethodInstance(classLoader);
                        XposedBridge.hookMethod(reflectMethod, createStoryHideHook(methodName, "🚫 This user hid their story from you"));
                        hookedCount++;
                        XposedBridge.log(TAG + " ✅ Hooked hide_story method by name: " + methodName + " (index: " + i + ")");
                    } catch (Throwable e) {
                        XposedBridge.log(TAG + " ❌ Failed to hook hide_story method: " + e.getMessage());
                    }
                }
                
                // Check for is_muting_reel related method
                if (methodLower.contains("mutingreel") || methodLower.contains("muting_reel") || 
                    (methodLower.contains("mute") && methodLower.contains("reel"))) {
                    try {
                        Method reflectMethod = methodData.getMethodInstance(classLoader);
                        XposedBridge.hookMethod(reflectMethod, createStoryHideHook(methodName, "🔇 This user muted your stories"));
                        hookedCount++;
                        XposedBridge.log(TAG + " ✅ Hooked is_muting_reel method by name: " + methodName + " (index: " + i + ")");
                    } catch (Throwable e) {
                        XposedBridge.log(TAG + " ❌ Failed to hook is_muting_reel method: " + e.getMessage());
                    }
                }
                
                // Check for is_blocking_reel related method
                if (methodLower.contains("blockingreel") || methodLower.contains("blocking_reel") || 
                    (methodLower.contains("block") && methodLower.contains("reel"))) {
                    try {
                        Method reflectMethod = methodData.getMethodInstance(classLoader);
                        XposedBridge.hookMethod(reflectMethod, createStoryHideHook(methodName, "⛔ This user blocked your stories"));
                        hookedCount++;
                        XposedBridge.log(TAG + " ✅ Hooked is_blocking_reel method by name: " + methodName + " (index: " + i + ")");
                    } catch (Throwable e) {
                        XposedBridge.log(TAG + " ❌ Failed to hook is_blocking_reel method: " + e.getMessage());
                    }
                }
            }

            // Strategy 2: If no methods found by name, try index-based approach
            // Based on typical Instagram structure, we try common indices
            if (hookedCount == 0) {
                XposedBridge.log(TAG + " No methods found by name, trying index-based approach...");
                
                // Try to hook all Boolean methods and log their values when called
                // This helps identify which method corresponds to which field
                for (int i = 0; i < friendshipMethods.size(); i++) {
                    final int index = i;
                    MethodData methodData = friendshipMethods.get(i);
                    String methodName = methodData.getName();
                    
                    try {
                        Method reflectMethod = methodData.getMethodInstance(classLoader);
                        XposedBridge.hookMethod(reflectMethod, new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                try {
                                    if (!FeatureFlags.showStoryHideToast) {
                                        return;
                                    }

                                    Boolean result = (Boolean) param.getResult();
                                    
                                    // Log all method calls with their results for debugging
                                    // This helps identify which index corresponds to which field
                                    if (result != null && result) {
                                        String targetId = ps.reso.instaeclipse.utils.tracker.FollowIndicatorTracker.currentlyViewedUserId;
                                        XposedBridge.log(TAG + " Method[" + index + "] " + methodName + " = true, userId: " + targetId);
                                        
                                        // Only show toast if we have a target user ID
                                        if (targetId == null || targetId.isEmpty()) {
                                            return;
                                        }

                                        // Avoid duplicate toasts
                                        String key = targetId + "_" + methodName;
                                        if (shownUsers.contains(key)) {
                                            return;
                                        }
                                        shownUsers.add(key);

                                        // For now, show a generic message with the method name
                                        // User can report which method index shows incorrectly
                                        Context context = AndroidAppHelper.currentApplication().getApplicationContext();
                                        String toastMessage = "⚠️ Story status detected (method[" + index + "]: " + methodName + ")";
                                        CustomToast.showCustomToast(context, toastMessage);
                                    }
                                } catch (Throwable e) {
                                    XposedBridge.log(TAG + " Error in hook callback: " + e.getMessage());
                                }
                            }
                        });
                        hookedCount++;
                        XposedBridge.log(TAG + " Hooked method[" + i + "]: " + methodName);
                    } catch (Throwable e) {
                        XposedBridge.log(TAG + " Failed to hook method[" + i + "]: " + e.getMessage());
                    }
                }
            }

            if (hookedCount > 0) {
                FeatureStatusTracker.setHooked("ShowStoryHideToast");
                XposedBridge.log(TAG + " ✅ Story hide detection enabled (" + hookedCount + " methods hooked)");
                XposedBridge.log(TAG + " ⚠️ Please check logs to identify correct method indices");
            } else {
                XposedBridge.log(TAG + " ⚠️ No methods found to hook");
            }

        } catch (Throwable e) {
            XposedBridge.log(TAG + " ❌ Exception: " + e.getMessage());
        }
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
                        XposedBridge.log(TAG + " No targetId, skipping toast for method: " + methodName);
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