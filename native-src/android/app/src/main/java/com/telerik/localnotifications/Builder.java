package com.telerik.localnotifications;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.support.annotation.Nullable;
import android.support.v4.app.NotificationCompat;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Random;
import java.util.concurrent.ExecutionException;

import static android.app.PendingIntent.FLAG_UPDATE_CURRENT;

public final class Builder {

    private static final String TAG = "Builder";
    private static final String DEFAULT_CHANNEL = "Notifications";

    // To generate unique request codes:
    private static final Random RANDOM = new Random();

    // Methods to build notifications:

    static Notification build(JSONObject options, Context context, int notificationID) {
        // We use options.channel as both channel id and name. If not set, both default to DEFAULT_CHANNEL:
        return build(options, context, notificationID, options.optString("channel", DEFAULT_CHANNEL));
    }

    static Notification build(JSONObject options, Context context, int notificationID, String channelID) {
        // Set channel for Android 8+:

        if (android.os.Build.VERSION.SDK_INT >= 26) {
            final NotificationManager notificationManager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);

            if (notificationManager != null && notificationManager.getNotificationChannel(channelID) == null) {
                notificationManager.createNotificationChannel(new NotificationChannel(channelID, channelID, NotificationManager.IMPORTANCE_HIGH));
            }
        }

        // Create the builder:

        NotificationCompat.Builder builder = android.os.Build.VERSION.SDK_INT >= 26 ? new NotificationCompat.Builder(context, channelID) : new NotificationCompat.Builder(context);

        builder
            .setDefaults(0)
            .setContentTitle(options.optString("title", null))
            .setSubText(options.optString("subtitle", null))
            .setContentText(options.optString("body", null))
            .setSmallIcon(options.optInt("icon"))
            .setAutoCancel(true) // Remove the notification from the status bar once tapped.
            .setNumber(options.optInt("badge"))
            .setColor(options.optInt("color"))
            .setOngoing(options.optBoolean("ongoing"))
            .setPriority(options.optBoolean("forceShowWhenInForeground") ? 1 : 0)
            .setTicker(options.optString("ticker", null)); // Let the OS handle the default value for the ticker.

        final Object thumbnail = options.opt("thumbnail");

        if (thumbnail instanceof String) {
            builder.setLargeIcon(getBitmap(context, (String) thumbnail));
        }

        // TODO sound preference is not doing anything
        // builder.setSound(options.has("sound") ? Uri.parse("android.resource://" + context.getPackageName() + "/raw/" + options.getString("sound")) : Uri.parse("android.resource://" + context.getPackageName() + "/raw/notify"))
        if (options.has("sound")) {
            builder.setSound(android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_NOTIFICATION));
        }

        applyContentReceiver(options, builder, context, notificationID);
        applyDeleteReceiver(builder, context, notificationID);
        applyStyle(options, builder, context);
        applyActions(options, builder, context);

        return builder.build();
    }


    // Notification click and cancel handlers:

    /**
     * Add the intent that handles the event when the notification is clicked (which should launch the app).
     */
    private static void applyContentReceiver(JSONObject options, NotificationCompat.Builder builder, Context context, int notificationID) {
        final Intent intent = new Intent(context, NotificationClickedReceiver.class)
                .putExtra(NotificationPublisher.NOTIFICATION_ID, notificationID)
                .putExtra(Action.EXTRA_ID, Action.CLICK_ACTION_ID)
                .putExtra("NOTIFICATION_LAUNCH", options.optBoolean("launch", true))
                .setFlags(Intent.FLAG_ACTIVITY_NO_HISTORY);

        final PendingIntent pendingContentIntent = PendingIntent.getService(context, RANDOM.nextInt(), intent, FLAG_UPDATE_CURRENT);
        builder.setContentIntent(pendingContentIntent);
    }

    /**
    * Add the intent that handles the delete event (which is fired when the X or 'clear all'
    * was pressed in the notification center).
    */
    private static void applyDeleteReceiver(NotificationCompat.Builder builder, Context context, int notificationID) {
        final Intent intent = new Intent(context, NotificationClearedReceiver.class)
            .setAction(String.valueOf(notificationID))
            .putExtra(Action.EXTRA_ID, notificationID);

        final PendingIntent deleteIntent = PendingIntent.getBroadcast(context, RANDOM.nextInt(), intent, FLAG_UPDATE_CURRENT);
        builder.setDeleteIntent(deleteIntent);
    }


    // Notification styles:

    private static void applyStyle(JSONObject options, NotificationCompat.Builder builder, Context context) {
        if (options.has("groupedMessages")) {
            applyGroup(options, builder);
        } else if (options.optBoolean("bigTextStyle")) {
            applyBigTextStyle(options, builder);
        } else if (options.has("image")) {
            applyImage(options, builder, context);
        }
    }

    private static void applyImage(JSONObject options, NotificationCompat.Builder builder, Context context) {
        Bitmap bitmap = getBitmap(context, options.optString("image", ""));

        if (bitmap == null) {
            return;
        }

        final NotificationCompat.BigPictureStyle bigPictureStyle = new NotificationCompat.BigPictureStyle().bigPicture(bitmap);

        builder.setStyle(bigPictureStyle);

        final Object thumbnail = options.opt("thumbnail");

        if (Boolean.TRUE.equals(thumbnail)) {
            builder.setLargeIcon(bitmap); // Set the thumbnail...
            bigPictureStyle.bigLargeIcon(null); // ...which goes away when expanded.
        }

    }

    private static void applyBigTextStyle(JSONObject options, NotificationCompat.Builder builder) {
        // set big text style (adds an 'expansion arrow' to the notification)
        if (options.optBoolean("bigTextStyle")) {
            final NotificationCompat.BigTextStyle bigTextStyle = new NotificationCompat.BigTextStyle();
            bigTextStyle.setBigContentTitle(options.optString("title"));
            bigTextStyle.bigText(options.optString("body"));
            builder.setStyle(bigTextStyle);
        }
    }

    private static void applyGroup(JSONObject options, NotificationCompat.Builder builder) {
        JSONArray groupedMessages = options.optJSONArray("groupedMessages");

        if (groupedMessages == null) {
            return;
        }

        final NotificationCompat.InboxStyle inboxStyle = new NotificationCompat.InboxStyle();

        // Sets a title for the Inbox in expanded layout
        // TODO: Is this needed? Should we add a different option for it (bigTitle)?
        inboxStyle.setBigContentTitle(options.optString("title", null));
        inboxStyle.setSummaryText(options.optString("groupSummary", null));

        int messagesToDisplay = Math.min(groupedMessages.length(), 5);

        for (int i = 0; i < messagesToDisplay; ++i) {
            try {
                inboxStyle.addLine(groupedMessages.getString(i));
            } catch (JSONException e) {
                // Just continue...
            }

        }

        builder
            .setGroup("myGroup") // TODO not sure this needs to be configurable
            .setStyle(inboxStyle);
    }

    private static void applyActions(JSONObject options, NotificationCompat.Builder builder, Context context) {
        Action[] actions = getActions(options, context);

        if (actions == null || actions.length == 0) {
            return;
        }

        NotificationCompat.Action.Builder btn;
        for (Action action : actions) {
            btn = new NotificationCompat.Action.Builder(
                    action.getIcon(),
                    action.getTitle(),
                    getPendingIntentForAction(options, context, action));

            if (action.isWithInput()) {
                Log.d(TAG, "applyActions, isWithInput");
                btn.addRemoteInput(action.getInput());
            } else {
                Log.d(TAG, "applyActions, not isWithInput");
            }

            builder.addAction(btn.build());
        }
    }

    private static Action[] getActions(JSONObject options, Context context) {
        Object value = options.opt("actions");
        String groupId = null;
        JSONArray actions = null;
        ActionGroup group = null;

        if (value instanceof String) {
            groupId = (String) value;
        } else if (value instanceof JSONArray) {
            actions = (JSONArray) value;
        }

        if (groupId != null) {
            group = ActionGroup.lookup(groupId);
        } else if (actions != null && actions.length() > 0) {
            group = ActionGroup.parse(context, actions);
        }

        return (group != null) ? group.getActions() : null;
    }

    private static PendingIntent getPendingIntentForAction(JSONObject options, Context context, Action action) {
        Log.d(TAG, "getPendingIntentForAction action.id " + action.getId() + ", action.isLaunchingApp(): " + action.isLaunchingApp());
        Intent intent = new Intent(context, NotificationClickedReceiver.class)
                .putExtra(NotificationPublisher.NOTIFICATION_ID, options.optInt("id", 0))
                .putExtra(Action.EXTRA_ID, action.getId())
                // TODO see https://github.com/katzer/cordova-plugin-local-notifications/blob/ca1374325bb27ec983332d55dcb6975d929bca4b/src/android/notification/Builder.java#L396
                .putExtra("NOTIFICATION_LAUNCH", action.isLaunchingApp())
                .setFlags(Intent.FLAG_ACTIVITY_NO_HISTORY);

        int reqCode = RANDOM.nextInt();

        return PendingIntent.getService(context, reqCode, intent, FLAG_UPDATE_CURRENT);
    }

    // Utility methods:

    private static @Nullable Bitmap getBitmap(Context context, String src) {
        if (src.indexOf("res://") == 0) {
            final int resourceId = context.getResources().getIdentifier(src.substring(6), "drawable", context.getApplicationInfo().packageName);

            return resourceId == 0 ? null : android.graphics.BitmapFactory.decodeResource(context.getResources(), resourceId);
        } else if (src.indexOf("http") == 0) {
            try {
                return new DownloadFileFromUrl(src).execute().get();
            } catch (InterruptedException | ExecutionException e) {
                return null;
            }
        }

        return null;
    }
}
