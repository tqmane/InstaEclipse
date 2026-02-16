package ps.reso.instaeclipse.mods.misc;

import android.app.AndroidAppHelper;
import android.content.Context;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;

import java.util.ArrayList;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.toast.CustomToast;

public class FollowerIndicator {

    public String type;

    public FollowMethodResult findFollowerStatusMethod(DexKitBridge bridge) {
        try {

            // Step 1:  Get the Boolean methods in FriendshipStatus
            try {

                // Find all methods declared inside com.instagram.user.model.FriendshipStatus
                List<MethodData> friendshipMethods = bridge.findMethod(FindMethod.create().matcher(MethodMatcher.create().declaredClass("com.instagram.user.model.FriendshipStatus").returnType("java.lang.Boolean")));

                if (friendshipMethods.size() >= 2) {
                    MethodData followedByMethod = friendshipMethods.get(1); // 2nd Boolean-returning method = followed_by
                    String isBlockingReelMethod = null;
                    if (friendshipMethods.size() >= 14) {
                        isBlockingReelMethod = friendshipMethods.get(13).getName(); // 14th Boolean-returning method = is_blocking_reel
                    }
                    type = "default";
                    return new FollowMethodResult(followedByMethod.getName(), isBlockingReelMethod, followedByMethod.getClassName());
                }

            } catch (Throwable ignore) {
            }

            try {
                // Step 2: Try method detection (obfuscated User class)
                String obfUserClass = null;
                List<MethodData> errMethods = bridge.findMethod(FindMethod.create().matcher(MethodMatcher.create().usingStrings("ERROR_INSERT_EXPIRED_URL")));
                if (!errMethods.isEmpty()) {
                    obfUserClass = errMethods.get(0).getClassName();
                }

                if (obfUserClass != null) {
                    List<MethodData> methods = bridge.findMethod(FindMethod.create().matcher(MethodMatcher.create().usingStrings("", "", "").paramTypes("com.instagram.common.session.UserSession", obfUserClass)));

                    for (MethodData method : methods) {

                        for (MethodData invoked : method.getInvokes()) {
                            String className = invoked.getClassName();
                            String returnType = String.valueOf(invoked.getReturnType());

                            if (className.contains(obfUserClass) && returnType.contains("boolean")) {
                                type = "fallback - 1";
                                return new FollowMethodResult(invoked.getName(), null, obfUserClass);
                            }
                        }
                    }
                }
            } catch (Throwable ignore) {
            }

            try {
                // Step 3: Fallback to old detection
                List<MethodData> methodsOld = bridge.findMethod(FindMethod.create().matcher(MethodMatcher.create().usingStrings("", "", "").paramCount(2)));

                for (MethodData method : methodsOld) {
                    List<String> paramTypes = new ArrayList<>();
                    for (Object param : method.getParamTypes()) {
                        paramTypes.add(String.valueOf(param));
                    }
                    if (paramTypes.size() == 2 && paramTypes.get(0).contains("com.instagram.common.session.UserSession") && paramTypes.get(1).contains("com.instagram.user.model.User")) {
                        for (MethodData invoked : method.getInvokes()) {
                            if (invoked.getClassName().contains("com.instagram.user.model.User") && String.valueOf(invoked.getReturnType()).contains("boolean")) {
                                type = "fallback - 2";
                                return new FollowMethodResult(invoked.getName(), null, "com.instagram.user.model.User");
                            }
                        }
                    }
                }
            } catch (Throwable ignore) {
            }

        } catch (Throwable e) {
            XposedBridge.log("❌ Error in findFollowerStatusMethod: " + e.getMessage());
        }
        return null;
    }

    public String findUserIdClassIfNeeded(DexKitBridge bridge, String userClassName) {

        try {

            if (!"com.instagram.user.model.FriendshipStatus".equals(userClassName)) {
                // Step 2 / Step 3 found a usable class — no need to search again
                return userClassName;
            } else {
                String userClass = null;

                try {
                    // Find method referencing "username_missing_during_update"
                    List<MethodData> methods = bridge.findMethod(FindMethod.create().matcher(MethodMatcher.create().usingStrings("username_missing_during_update")));

                    if (!methods.isEmpty()) {
                        MethodData m = methods.get(0);
                        userClass = m.getClassName();

                        // Verify toString exists
                        List<MethodData> toStringMethods = bridge.findMethod(FindMethod.create().matcher(MethodMatcher.create().declaredClass(userClass).name("toString").returnType("java.lang.String")));

                        if (!toStringMethods.isEmpty()) {
                            toStringMethods.get(0).getName();
                            MethodData toStringMethodData = toStringMethods.get(0); // keep MethodData

                            // Inspect what toString() calls internally
                            List<MethodData> invokedByToString = toStringMethodData.getInvokes();
                            for (MethodData invoked : invokedByToString) {

                                // return invoked class
                                return invoked.getClassName();

                            }
                        }
                    }

                } catch (Throwable e) {
                    XposedBridge.log("❌ Error finding user class via 'username_missing_during_update': " + e.getMessage());
                }
                return userClass;
            }

        } catch (Throwable ignore) {
        }


        return null;
    }

    public void checkFollow(ClassLoader classLoader, String followerStatusMethod, String isBlockingReelMethod, String userClassName, String userIdClassName) {
        try {

            final String[] userId = {null};

            // If DexKit gave us the interface, switch to the implementation
            if (userClassName.equals("com.instagram.user.model.FriendshipStatus")) {
                //userClassName = userIdClassName;
                userClassName = "com.instagram.user.model.FriendshipStatusImpl";
                try {
                    if (userIdClassName != null) {
                        final String methodName = "getId";

                        // Hook into that class’s toString()
                        XposedHelpers.findAndHookMethod(userIdClassName, classLoader, methodName, new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                userId[0] = (String) param.getResult();

                            }
                        });
                    }
                } catch (Throwable t) {
                    XposedBridge.log("❌ Failed to hook toString() fallback: " + t.getMessage());
                }
            }

            String finalUserClassName = userClassName;
            
            // Hook Follower Status
            XposedHelpers.findAndHookMethod(userClassName, classLoader, followerStatusMethod, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Object user = param.thisObject;

                    if (!finalUserClassName.equals("com.instagram.user.model.FriendshipStatusImpl")) {
                        try {
                            // Try the usual User.getId()
                            userId[0] = (String) XposedHelpers.callMethod(param.thisObject, "getId");
                        } catch (Throwable ignored) {

                        }
                    }

                    String username = null;
                    try {
                        username = (String) XposedHelpers.callMethod(user, "getUsername");
                    } catch (Throwable ignored) {
                        // skip username for now in obfuscated versions
                    }

                    Boolean followsMe = (Boolean) param.getResult();
                    String targetId = ps.reso.instaeclipse.utils.tracker.FollowIndicatorTracker.currentlyViewedUserId;
                    try {
                        if (userId[0] != null && userId[0].equals(targetId) && ps.reso.instaeclipse.utils.feature.FeatureFlags.showFollowerToast) {
                            Context context = AndroidAppHelper.currentApplication().getApplicationContext();
                            String message;
                            if (username != null && !username.isEmpty()) {
                                message = "@" + username + " (" + userId[0] + ") " + (followsMe ? "follows you ✅" : "doesn’t follow you ❌");
                            } else {
                                message = " (" + userId[0] + ") " + (followsMe ? "follows you ✅" : "doesn’t follow you ❌");
                            }
                            CustomToast.showCustomToast(context, message);
                            
                            // Nullify after a short delay to allow story hook to run
                            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                                if (ps.reso.instaeclipse.utils.tracker.FollowIndicatorTracker.currentlyViewedUserId != null && ps.reso.instaeclipse.utils.tracker.FollowIndicatorTracker.currentlyViewedUserId.equals(userId[0])) {
                                    ps.reso.instaeclipse.utils.tracker.FollowIndicatorTracker.currentlyViewedUserId = null;
                                }
                            }, 2000);
                        }
                    } catch (Throwable ignore) {

                    }

                }
            });

            // Hook Story Hidden Status
            if (isBlockingReelMethod != null) {
                XposedHelpers.findAndHookMethod(userClassName, classLoader, isBlockingReelMethod, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Boolean isBlockingReel = (Boolean) param.getResult();
                        String targetId = ps.reso.instaeclipse.utils.tracker.FollowIndicatorTracker.currentlyViewedUserId;
                        
                        if (targetId != null && isBlockingReel != null && isBlockingReel && ps.reso.instaeclipse.utils.feature.FeatureFlags.showStoryHiddenToast) {
                             if (!targetId.equals(ps.reso.instaeclipse.utils.tracker.FollowIndicatorTracker.lastStoryToastId)) {
                                 Context context = AndroidAppHelper.currentApplication().getApplicationContext();
                                 CustomToast.showCustomToast(context, "This user has hidden their story from you! 🚫");
                                 ps.reso.instaeclipse.utils.tracker.FollowIndicatorTracker.lastStoryToastId = targetId;
                             }
                        }
                    }
                });
                FeatureStatusTracker.setHooked("ShowStoryHiddenToast");
            }


            XposedBridge.log("(InstaEclipse | FollowerStatus): ✅ Hooked (" + type + "): " + userClassName + "." + followerStatusMethod);
            FeatureStatusTracker.setHooked("ShowFollowerToast");

        } catch (Exception e) {
            XposedBridge.log("❌ Error hooking follower status: " + e.getMessage());
        }
    }

    public static class FollowMethodResult {
        public final String methodName;
        public final String isBlockingReelMethodName;
        public final String userClassName;

        public FollowMethodResult(String methodName, String isBlockingReelMethodName, String userClassName) {
            this.methodName = methodName;
            this.isBlockingReelMethodName = isBlockingReelMethodName;
            this.userClassName = userClassName;
        }
    }
}
