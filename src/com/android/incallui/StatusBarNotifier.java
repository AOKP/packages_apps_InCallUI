/*
 * Copyright (C) 2013 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.incallui;

import com.android.services.telephony.common.CallIdentification;
import com.google.common.base.Preconditions;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.text.TextUtils;

import com.android.incallui.ContactInfoCache.ContactCacheEntry;
import com.android.incallui.ContactInfoCache.ContactInfoCacheCallback;
import com.android.incallui.InCallApp.NotificationBroadcastReceiver;
import com.android.incallui.InCallPresenter.InCallState;
import com.android.services.telephony.common.Call;

/**
 * This class adds Notifications to the status bar for the in-call experience.
 */
public class StatusBarNotifier implements InCallPresenter.InCallStateListener,
        InCallPresenter.IncomingCallListener {
    // notification types
    private static final int IN_CALL_NOTIFICATION = 1;

    private final Context mContext;
    private final ContactInfoCache mContactInfoCache;
    private final CallList mCallList;
    private final NotificationManager mNotificationManager;
    private boolean mIsShowingNotification = false;
    private InCallState mInCallState = InCallState.HIDDEN;
    private int mSavedIcon = 0;
    private int mSavedContent = 0;
    private Bitmap mSavedLargeIcon;
    private String mSavedContentTitle;

    public StatusBarNotifier(Context context, ContactInfoCache contactInfoCache,
            CallList callList) {
        Preconditions.checkNotNull(context);

        mContext = context;
        mContactInfoCache = contactInfoCache;
        mCallList = callList;
        mNotificationManager =
                (NotificationManager) mContext.getSystemService(Context.NOTIFICATION_SERVICE);
    }

    /**
     * Creates notifications according to the state we receive from {@link InCallPresenter}.
     */
    @Override
    public void onStateChange(InCallState state, CallList callList) {
        updateNotification(state, callList);
    }

    @Override
    public void onIncomingCall(final Call call) {
        final ContactCacheEntry entry = ContactInfoCache.buildCacheEntryFromCall(mContext,
                call.getIdentification(), true);

        // Initial update with no contact information.
        buildAndSendNotification(InCallState.INCOMING, call, entry, false);

        // TODO(klp): InCallPresenter already calls updateNofication() when it wants to start
        // the notification. We shouldn't do this twice.
        // TODO(klp): This search doesn't happen for outgoing calls any more.  It works because
        // the call card makes a requests that are cached...but eventually this startup process
        // needs to incorporate call searches for all new calls, not just incoming.

        // we make a call to the contact info cache to query for supplemental data to what the
        // call provides.  This includes the contact name and photo.
        // This callback will always get called immediately and synchronously with whatever data
        // it has available, and may make a subsequent call later (same thread) if it had to
        // call into the contacts provider for more data.
        mContactInfoCache.findInfo(call.getIdentification(), true, new ContactInfoCacheCallback() {
            @Override
            public void onContactInfoComplete(int callId, ContactCacheEntry entry) {
                buildAndSendNotification(InCallState.INCOMING, call, entry, false);
            }
        });
    }

    /**
     * Updates the phone app's status bar notification based on the
     * current telephony state, or cancels the notification if the phone
     * is totally idle.
     *
     * This method will never actually launch the incoming-call UI.
     * (Use updateNotificationAndLaunchIncomingCallUi() for that.)
     */
    public void updateNotification(InCallState state, CallList callList) {
        // allowFullScreenIntent=false means *don't* allow the incoming
        // call UI to be launched.
        updateInCallNotification(false, state, callList);
    }

    /**
     * Updates the phone app's status bar notification *and* launches the
     * incoming call UI in response to a new incoming call.
     *
     * This is just like updateInCallNotification(), with one exception:
     * If an incoming call is ringing (or call-waiting), the notification
     * will also include a "fullScreenIntent" that will cause the
     * InCallScreen to be launched immediately, unless the current
     * foreground activity is marked as "immersive".
     *
     * (This is the mechanism that actually brings up the incoming call UI
     * when we receive a "new ringing connection" event from the telephony
     * layer.)
     *
     * Watch out: this method should ONLY be called directly from the code
     * path in CallNotifier that handles the "new ringing connection"
     * event from the telephony layer.  All other places that update the
     * in-call notification (like for phone state changes) should call
     * updateInCallNotification() instead.  (This ensures that we don't
     * end up launching the InCallScreen multiple times for a single
     * incoming call, which could cause slow responsiveness and/or visible
     * glitches.)
     *
     * Also note that this method is safe to call even if the phone isn't
     * actually ringing (or, more likely, if an incoming call *was*
     * ringing briefly but then disconnected).  In that case, we'll simply
     * update or cancel the in-call notification based on the current
     * phone state.
     *
     * @see #updateInCallNotification(boolean)
     */
    public void updateNotificationAndLaunchIncomingCallUi(InCallState state, CallList callList) {
        // Set allowFullScreenIntent=true to indicate that we *should*
        // launch the incoming call UI if necessary.
        updateInCallNotification(true, state, callList);
    }


    /**
     * Take down the in-call notification.
     * @see updateInCallNotification()
     */
    private void cancelInCall() {
        Log.d(this, "cancelInCall()...");
        mNotificationManager.cancel(IN_CALL_NOTIFICATION);

        mIsShowingNotification = false;
    }

    /**
     * Helper method for updateInCallNotification() and
     * updateNotificationAndLaunchIncomingCallUi(): Update the phone app's
     * status bar notification based on the current telephony state, or
     * cancels the notification if the phone is totally idle.
     *
     * @param allowFullScreenIntent If true, *and* an incoming call is
     *   ringing, the notification will include a "fullScreenIntent"
     *   pointing at the InCallActivity (which will cause the InCallActivity
     *   to be launched.)
     *   Watch out: This should be set to true *only* when directly
     *   handling a new incoming call for the first time.
     */
    private void updateInCallNotification(final boolean allowFullScreenIntent,
            final InCallState state, CallList callList) {
        Log.d(this, "updateInCallNotification(allowFullScreenIntent = "
                + allowFullScreenIntent + ")...");

        final Call call = getCallToShow(callList);
        if (shouldSuppressNotification(state, call)) {
            cancelInCall();
            return;
        }

        // Contact info should have already been done on incoming calls.
        // TODO(klp): This also needs to be done for outgoing calls.
        ContactCacheEntry entry = mContactInfoCache.getInfo(call.getCallId());
        if (entry == null) {
            entry = ContactInfoCache.buildCacheEntryFromCall(mContext, call.getIdentification(),
                    state == InCallState.INCOMING);
        }
        buildAndSendNotification(state, call, entry, allowFullScreenIntent);
    }

    /**
     * Sets up the main Ui for the notification
     */
    private void buildAndSendNotification(InCallState state, Call call,
            ContactCacheEntry contactInfo, boolean allowFullScreenIntent) {

        final int iconResId = getIconToDisplay(call);
        final Bitmap largeIcon = getLargeIconToDisplay(contactInfo);
        final int contentResId = getContentString(call);
        final String contentTitle = getContentTitle(contactInfo);

        // If we checked and found that nothing is different, dont issue another notification.
        if (!checkForChangeAndSaveData(iconResId, contentResId, largeIcon, contentTitle, state,
                allowFullScreenIntent)) {
            return;
        }

        /*
         * Nothing more to check...build and send it.
         */
        final Notification.Builder builder = getNotificationBuilder();

        // Set up the main intent to send the user to the in-call screen
        final PendingIntent inCallPendingIntent = createLaunchPendingIntent();
        builder.setContentIntent(inCallPendingIntent);

        // Set the intent as a full screen intent as well if requested
        if (allowFullScreenIntent) {
            configureFullScreenIntent(builder, inCallPendingIntent);
        }

        // set the content
        builder.setContentText(mContext.getString(contentResId));
        builder.setSmallIcon(iconResId);
        builder.setContentTitle(contentTitle);
        builder.setLargeIcon(largeIcon);

        if (call.getState() == Call.State.ACTIVE) {
            builder.setUsesChronometer(true);
            builder.setWhen(call.getConnectTime());
        } else {
            builder.setUsesChronometer(false);
        }

        // Add special Content for calls that are ongoing
        if (InCallState.INCALL == state || InCallState.OUTGOING == state) {
            addHangupAction(builder);
        }

        /*
         * Fire off the notification
         */
        Notification notification = builder.build();
        Log.d(this, "Notifying IN_CALL_NOTIFICATION: " + notification);
        mNotificationManager.notify(IN_CALL_NOTIFICATION, notification);
        mIsShowingNotification = true;
    }

    /**
     * Checks the new notification data and compares it against any notification that we
     * are already displaying. If the data is exactly the same, we return false so that
     * we do not issue a new notification for the exact same data.
     */
    private boolean checkForChangeAndSaveData(int icon, int content, Bitmap largeIcon,
            String contentTitle, InCallState state, boolean showFullScreenIntent) {

        // The two are different:
        // if new title is not null, it should be different from saved version OR
        // if new title is null, the saved version should not be null
        final boolean contentTitleChanged =
                (contentTitle != null && !contentTitle.equals(mSavedContentTitle)) ||
                (contentTitle == null && mSavedContentTitle != null);

        // any change means we are definitely updating
        boolean retval = (mSavedIcon != icon) || (mSavedContent != content) ||
                (mInCallState != state) || (mSavedLargeIcon != largeIcon) ||
                contentTitleChanged;

        // A full screen intent means that we have been asked to interrupt an activity,
        // so we definitely want to show it.
        if (showFullScreenIntent) {
            Log.d(this, "Forcing full screen intent");
            retval = true;
        }

        // If we aren't showing a notification right now, definitely start showing one.
        if (!mIsShowingNotification) {
            Log.d(this, "Showing notification for first time.");
            retval = true;
        }

        mSavedIcon = icon;
        mSavedContent = content;
        mInCallState = state;
        mSavedLargeIcon = largeIcon;
        mSavedContentTitle = contentTitle;

        if (retval) {
            Log.d(this, "Data changed.  Showing notification");
        }

        return retval;
    }

    /**
     * Returns the main string to use in the notification.
     */
    private String getContentTitle(ContactCacheEntry contactInfo) {
        if (TextUtils.isEmpty(contactInfo.name)) {
            return contactInfo.number;
        }

        return contactInfo.name;
    }

    /**
     * Gets a large icon from the contact info object to display in the notification.
     */
    private Bitmap getLargeIconToDisplay(ContactCacheEntry contactInfo) {
        if (contactInfo.photo != null && (contactInfo.photo instanceof BitmapDrawable)) {
            return ((BitmapDrawable) contactInfo.photo).getBitmap();
        }

        return null;
    }

    /**
     * Returns the appropriate icon res Id to display based on the call for which
     * we want to display information.
     */
    private int getIconToDisplay(Call call) {
        // Even if both lines are in use, we only show a single item in
        // the expanded Notifications UI.  It's labeled "Ongoing call"
        // (or "On hold" if there's only one call, and it's on hold.)
        // Also, we don't have room to display caller-id info from two
        // different calls.  So if both lines are in use, display info
        // from the foreground call.  And if there's a ringing call,
        // display that regardless of the state of the other calls.
        if (call.getState() == Call.State.ONHOLD) {
            return R.drawable.stat_sys_phone_call_on_hold;
        }
        return R.drawable.stat_sys_phone_call;
    }

    /**
     * Returns the message to use with the notificaiton.
     */
    private int getContentString(Call call) {
        int resId = R.string.notification_ongoing_call;

        if (call.getState() == Call.State.INCOMING) {
            resId = R.string.notification_incoming_call;

        } else if (call.getState() == Call.State.ONHOLD) {
            resId = R.string.notification_on_hold;

        } else if (call.getState() == Call.State.DIALING) {
            resId = R.string.notification_dialing;
        }

        return resId;
    }

    /**
     * Gets the most relevant call to display in the notification.
     */
    private Call getCallToShow(CallList callList) {
        Call call = callList.getIncomingCall();
        if (call == null) {
            call = callList.getOutgoingCall();
        }
        if (call == null) {
            call = callList.getActiveOrBackgroundCall();
        }
        return call;
    }

    private void addHangupAction(Notification.Builder builder) {
        Log.i(this, "Will show \"hang-up\" action in the ongoing active call Notification");

        // TODO: use better asset.
        builder.addAction(R.drawable.stat_sys_phone_call_end,
                mContext.getText(R.string.notification_action_end_call),
                createHangUpOngoingCallPendingIntent(mContext));
    }

    /**
     * Adds fullscreen intent to the builder.
     */
    private void configureFullScreenIntent(Notification.Builder builder, PendingIntent intent) {
        // Ok, we actually want to launch the incoming call
        // UI at this point (in addition to simply posting a notification
        // to the status bar).  Setting fullScreenIntent will cause
        // the InCallScreen to be launched immediately *unless* the
        // current foreground activity is marked as "immersive".
        Log.d(this, "- Setting fullScreenIntent: " + intent);
        builder.setFullScreenIntent(intent, true);

        // Ugly hack alert:
        //
        // The NotificationManager has the (undocumented) behavior
        // that it will *ignore* the fullScreenIntent field if you
        // post a new Notification that matches the ID of one that's
        // already active.  Unfortunately this is exactly what happens
        // when you get an incoming call-waiting call:  the
        // "ongoing call" notification is already visible, so the
        // InCallScreen won't get launched in this case!
        // (The result: if you bail out of the in-call UI while on a
        // call and then get a call-waiting call, the incoming call UI
        // won't come up automatically.)
        //
        // The workaround is to just notice this exact case (this is a
        // call-waiting call *and* the InCallScreen is not in the
        // foreground) and manually cancel the in-call notification
        // before (re)posting it.
        //
        // TODO: there should be a cleaner way of avoiding this
        // problem (see discussion in bug 3184149.)

        // TODO(klp): reenable this for klp
        /*if (incomingCall.getState() == Call.State.CALL_WAITING) {
            Log.i(this, "updateInCallNotification: call-waiting! force relaunch...");
            // Cancel the IN_CALL_NOTIFICATION immediately before
            // (re)posting it; this seems to force the
            // NotificationManager to launch the fullScreenIntent.
            mNotificationManager.cancel(IN_CALL_NOTIFICATION);
        }*/
    }

    private Notification.Builder getNotificationBuilder() {
        final Notification.Builder builder = new Notification.Builder(mContext);
        builder.setOngoing(true);

        // Make the notification prioritized over the other normal notifications.
        builder.setPriority(Notification.PRIORITY_HIGH);

        return builder;
    }

    /**
     * Returns true if notification should not be shown in the current state.
     */
    private boolean shouldSuppressNotification(InCallState state, Call call) {
        // Suppress the in-call notification if the InCallScreen is the
        // foreground activity, since it's already obvious that you're on a
        // call.  (The status bar icon is needed only if you navigate *away*
        // from the in-call UI.)
        boolean shouldSuppress = InCallPresenter.getInstance().isShowingInCallUi();

        // Suppress if the call is not active.
        if (!state.isConnectingOrConnected()) {
            Log.v(this, "suppressing: not connecting or connected");
            shouldSuppress = true;
        }

        // We can still be in the INCALL state when a call is disconnected (in order to show
        // the "Call ended" screen.  So check that we have an active connection too.
        if (call == null) {
            Log.v(this, "suppressing: no call");
            shouldSuppress = true;
        }

        // If there's an incoming ringing call: always show the
        // notification, since the in-call notification is what actually
        // launches the incoming call UI in the first place (see
        // notification.fullScreenIntent below.)  This makes sure that we'll
        // correctly handle the case where a new incoming call comes in but
        // the InCallScreen is already in the foreground.
        if (state.isIncoming()) {
            Log.v(this, "unsuppressing: incoming call");
            shouldSuppress = false;
        }

        // JANK Fix
        // Do not show the notification for outgoing calls until the UI comes up.
        // Since we don't normally show a notification while the incall screen is
        // in the foreground, if we show the outgoing notification before the activity
        // comes up the user will see it flash on and off on an outgoing call.
        // This code ensures that we do not show the notification for outgoing calls before
        // the activity has started.
        if (state == InCallState.OUTGOING && !InCallPresenter.getInstance().isActivityStarted()) {
            Log.v(this, "suppressing: activity not started.");
            shouldSuppress = true;
        }

        return shouldSuppress;
    }

    private PendingIntent createLaunchPendingIntent() {

        final Intent intent = new Intent(Intent.ACTION_MAIN, null);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                | Intent.FLAG_ACTIVITY_NO_USER_ACTION);
        intent.setClass(mContext, InCallActivity.class);

        // PendingIntent that can be used to launch the InCallActivity.  The
        // system fires off this intent if the user pulls down the windowshade
        // and clicks the notification's expanded view.  It's also used to
        // launch the InCallActivity immediately when when there's an incoming
        // call (see the "fullScreenIntent" field below).
        PendingIntent inCallPendingIntent = PendingIntent.getActivity(mContext, 0, intent, 0);

        return inCallPendingIntent;
    }

    /**
     * Returns PendingIntent for hanging up ongoing phone call. This will typically be used from
     * Notification context.
     */
    private static PendingIntent createHangUpOngoingCallPendingIntent(Context context) {
        final Intent intent = new Intent(InCallApp.ACTION_HANG_UP_ONGOING_CALL, null,
                context, NotificationBroadcastReceiver.class);
        return PendingIntent.getBroadcast(context, 0, intent, 0);
    }
}
