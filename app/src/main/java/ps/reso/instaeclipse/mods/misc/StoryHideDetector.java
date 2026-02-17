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
            
            // Log all method names for debugging
            StringBuilder methodList = new StringBuilder();
            for (int i = 0; i < friendshipMethods.size(); i++) {
                String methodName = friendshipMethods.get(i).getName();
                methodList.append("[").append(i).append(":").append(methodName).append("] ");
            }
            XposedBridge.log(TAG + " Methods: " + methodList.toString());

            int hookedCount = 0;

            // Hook ALL Boolean methods to log their values
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
                                String targetId = ps.reso.instaeclipse.utils.tracker.FollowIndicatorTracker.currentlyViewedUserId;
                                
                                // Log ALL calls for debugging
                                XposedBridge.log(TAG + " CALL: Method[" + index + "] " + methodName + " = " + result + ", userId: " + targetId);
                                
                                // Show toast for both true and false (for debugging)
                                // Only show toast if we have a target user ID
                                if (targetId == null || targetId.isEmpty()) {
                                    XposedBridge.log(TAG + " No targetId, skipping toast");
                                    return;
                                }

                                // Avoid duplicate toasts (but allow both true and false for same user)
                                String key = targetId + "_" + methodName + "_" + result;
                                if (shownUsers.contains(key)) {
                                    XposedBridge.log(TAG + " Already shown for this user+result, skipping");
                                    return;
                                }
                                shownUsers.add(key);

                                // Show toast with method info for debugging
                                String resultEmoji = (result != null && result) ? "✅" : "❌";
                                String toastMessage = resultEmoji + " [" + index + ":" + methodName + "] = " + result;

                                Context context = AndroidAppHelper.currentApplication().getApplicationContext();
                                CustomToast.showCustomToast(context, toastMessage);
                                XposedBridge.log(TAG + " TOAST: " + toastMessage);

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

            if (hookedCount > 0) {
                FeatureStatusTracker.setHooked("ShowStoryHideToast");
                XposedBridge.log(TAG + " ✅ Story hide detection enabled (" + hookedCount + " methods hooked)");
            } else {
                XposedBridge.log(TAG + " ⚠️ No methods found to hook");
            }

        } catch (Throwable e) {
            XposedBridge.log(TAG + " ❌ Exception: " + e.getMessage());
        }
    }

    /**
     * Clear the cache of shown users (call this when viewing a new profile)
     */
    public static void clearShownUsersCache() {
        shownUsers.clear();
    }
}