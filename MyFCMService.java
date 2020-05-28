package com.whizperapp.services;

import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;
import android.util.Log;

import com.whizperapp.model.constants.DBConstants;
import com.whizperapp.model.constants.DownloadUploadStat;
import com.whizperapp.model.constants.FireCallType;
import com.whizperapp.model.constants.MessageType;
import com.whizperapp.model.constants.OfficialEventTypes;
import com.whizperapp.model.constants.PendingGroupTypes;
import com.whizperapp.model.constants.PendingOfficialTypes;
import com.whizperapp.model.realms.FireCall;
import com.whizperapp.model.realms.GroupEvent;
import com.whizperapp.model.realms.Message;
import com.whizperapp.model.realms.Official;
import com.whizperapp.model.realms.OfficialEvent;
import com.whizperapp.model.realms.PendingGroupJob;
import com.whizperapp.model.realms.PendingOfficialJob;
import com.whizperapp.model.realms.PhoneNumber;
import com.whizperapp.model.realms.QuotedMessage;
import com.whizperapp.model.realms.RealmContact;
import com.whizperapp.model.realms.RealmLocation;
import com.whizperapp.model.realms.User;
import com.whizperapp.utils.DownloadManager;
import com.whizperapp.utils.FireManager;
import com.whizperapp.utils.JsonUtil;
import com.whizperapp.utils.ListUtil;
import com.whizperapp.utils.NotificationHelper;
import com.whizperapp.utils.OfficialManager;
import com.whizperapp.utils.RealmHelper;
import com.whizperapp.utils.ServiceHelper;
import com.whizperapp.utils.SharedPreferencesManager;
import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;
import com.sinch.android.rtc.NotificationResult;
import com.sinch.android.rtc.SinchHelpers;
import com.sinch.android.rtc.calling.CallNotificationResult;

import java.util.ArrayList;
import java.util.Map;

import io.realm.RealmList;

public class MyFCMService extends FirebaseMessagingService {


    @Override
    public void onNewToken(String s) {
        super.onNewToken(s);
        SharedPreferencesManager.setTokenSaved(false);

        ServiceHelper.saveToken(this, s);
    }


    @Override
    public void onMessageReceived(final RemoteMessage remoteMessage) {
        super.onMessageReceived(remoteMessage);

        Map data = remoteMessage.getData();

        //if this payload is Sinch Call
        if (SinchHelpers.isSinchPushPayload(remoteMessage.getData())) {
            new ServiceConnection() {

                private Map payload;

                @Override
                public void onServiceConnected(ComponentName name, IBinder service) {
                    if (payload != null) {
                        CallingService.SinchServiceInterface sinchService = (CallingService.SinchServiceInterface) service;
                        if (sinchService != null) {
                            NotificationResult result = sinchService.relayRemotePushNotificationPayload(payload);
                            //if the Messages is a call
                            if (result.isValid() && result.isCall()) {

                                CallNotificationResult callResult = result.getCallResult();
                                String callId = callResult.getCallId();

                                //if this call was missed (user did not answer)
                                if (callResult.isCallCanceled()) {
                                    RealmHelper.getInstance().setCallAsMissed(callId);
                                    User user = RealmHelper.getInstance().getUser(callResult.getRemoteUserId());
                                    FireCall fireCall = RealmHelper.getInstance().getFireCall(callId);
                                    if (user != null && fireCall != null) {
                                        String phoneNumber = fireCall.getPhoneNumber();
                                        new NotificationHelper(MyFCMService.this).createMissedCallNotification(user, phoneNumber);
                                    }
                                } else {
                                    Map<String, String> headers = callResult.getHeaders();
                                    if (!headers.isEmpty()) {
                                        String phoneNumber = headers.get("phoneNumber");
                                        String timestampStr = headers.get("timestamp");
                                        if (phoneNumber != null && timestampStr != null) {
                                            long timestamp = Long.parseLong(timestampStr);
                                            User user = RealmHelper.getInstance().getUser(callResult.getRemoteUserId());
                                            FireCall fireCall = new FireCall(callId, user, FireCallType.INCOMING, timestamp, phoneNumber, callResult.isVideoOffered());
                                            RealmHelper.getInstance().saveObjectToRealm(fireCall);
                                        }
                                    }
                                }
                            }
                        }
                    }
                    payload = null;
                }

                @Override
                public void onServiceDisconnected(ComponentName name) {
                }

                public void relayMessageData(Map<String, String> data) {
                    payload = data;
                    Intent intent = new Intent(getApplicationContext(), CallingService.class);
                    getApplicationContext().bindService(intent, this, BIND_AUTO_CREATE);
                }
            }.relayMessageData(data);

        }
        else if (remoteMessage.getData().containsKey("event")) {
            //this will called when something is changed in group.
            // like member removed,added,admin changed, group info changed...
            if (remoteMessage.getData().get("event").equals("group_event")) {
                String groupId = remoteMessage.getData().get("groupId");
                String eventId = remoteMessage.getData().get("eventId");
                String contextStart = remoteMessage.getData().get("contextStart");
                int eventType = Integer.parseInt(remoteMessage.getData().get("eventType"));
                String contextEnd = remoteMessage.getData().get("contextEnd");

                //if this event was by the admin himself  OR if the event already exists do nothing
                if (contextStart.equals(SharedPreferencesManager.getPhoneNumber())
                        || RealmHelper.getInstance().getMessage(eventId) != null) {
                    return;
                }

                GroupEvent groupEvent = new GroupEvent(contextStart, eventType, contextEnd, eventId);
                PendingGroupJob pendingGroupJob = new PendingGroupJob(groupId, PendingGroupTypes.CHANGE_EVENT, groupEvent);
                RealmHelper.getInstance().saveObjectToRealm(pendingGroupJob);
                ServiceHelper.updateGroupInfo(this, groupId, groupEvent);

            }

            else if (remoteMessage.getData().get("event").equals("new_group")) {

                String groupId = remoteMessage.getData().get("groupId");
                User user = RealmHelper.getInstance().getUser(groupId);

                //if the group is not exists,fetch and download it
                if (user == null) {
                    PendingGroupJob pendingGroupJob = new PendingGroupJob(groupId, PendingGroupTypes.CREATION_EVENT, null);
                    RealmHelper.getInstance().saveObjectToRealm(pendingGroupJob);
                    ServiceHelper.fetchAndCreateGroup(this, groupId);
                } else {

                    RealmList<User> users = user.getGroup().getUsers();
                    User userById = ListUtil.getUserById(FireManager.getUid(), users);

                    //if the group is not active or the group does not contain current user
                    // then fetch and download it and set it as Active
                    if (!user.getGroup().isActive() || !users.contains(userById)) {
                        PendingGroupJob pendingGroupJob = new PendingGroupJob(groupId, PendingGroupTypes.CREATION_EVENT, null);
                        RealmHelper.getInstance().saveObjectToRealm(pendingGroupJob);
                        ServiceHelper.fetchAndCreateGroup(this, groupId);
                    }
                }


            }

            else if (remoteMessage.getData().get("event").equals("invite_official")) {
                String officialId = remoteMessage.getData().get("officialId");
                String userIdInOfficial = remoteMessage.getData().get("userCode");
                User officialUser = RealmHelper.getInstance().getUser(officialId);
                if (officialUser==null || officialUser.getOfficial()==null)
                {
                    Log.i("MyFCMService", "handleNewInvites: official not exists");

                    //if the official is not exists, fetch and download it
//                    OfficialEvent officialEvent = new OfficialEvent(contextStart, eventType, contextEnd, eventId);
                    PendingOfficialJob pendingOfficialJob = new PendingOfficialJob(officialId, userIdInOfficial,
                            PendingOfficialTypes.CREATION_NOT_JOINED_EVENT, null);
                    RealmHelper.getInstance().saveObjectToRealm(pendingOfficialJob);
                    ServiceHelper.fetchAndCreateOfficial(this, officialId, userIdInOfficial, false);

                } else if (officialUser.getOfficial().isJoined()) {
                    //do nothing
                } else
                    new NotificationHelper(this).fireNotificationInviteOfficial(officialId);

            }
            else if (remoteMessage.getData().get("event").equals("official_event")) {

                String officialId = remoteMessage.getData().get("officialId");
                String eventId = remoteMessage.getData().get("eventId");
                String contextStart = remoteMessage.getData().get("contextStart");
                int eventType = Integer.parseInt(remoteMessage.getData().get("eventType"));
                String contextEnd = remoteMessage.getData().get("contextEnd");

                Log.i("fcmservice", "official event" + eventId + eventType + officialId);

                switch (eventType){
                    case OfficialEventTypes.JOINED_OFFICIAL:
                    case OfficialEventTypes.EXIT_OFFICIAL:
                        new NotificationHelper(this).handleOfficialEvents(officialId, contextEnd, eventType);
                        break ;
                    default:
                        OfficialEvent officialEvent = new OfficialEvent(contextStart, eventType, contextEnd, eventId);
                        PendingOfficialJob pendingOfficialJob = new PendingOfficialJob(officialId, null, PendingOfficialTypes.CHANGE_EVENT, officialEvent);
                        RealmHelper.getInstance().saveObjectToRealm(pendingOfficialJob);
                        ServiceHelper.updateOfficialInfo(this, officialId, officialEvent);
                }


            }

            else if (remoteMessage.getData().get("event").equals("message_deleted")) {
                String messageId = remoteMessage.getData().get("messageId");
                Message message = RealmHelper.getInstance().getMessage(messageId);
                RealmHelper.getInstance().setMessageDeleted(messageId);

                if (message != null) {
                    if (message.getDownloadUploadStat() == DownloadUploadStat.LOADING) {
                        if (MessageType.isSentType(message.getType())) {
                            DownloadManager.cancelUpload(message.getMessageId());
                        } else
                            DownloadManager.cancelDownload(message.getMessageId());
                    }
                    new NotificationHelper(this).messageDeleted(message);
                }
            }

            else if (remoteMessage.getData().get("event").equals("call")) {


            }
            //perubahan di official
            if(remoteMessage.getData().get("event").equals("change_field_detail")){
                String officialId = remoteMessage.getData().get("officialId");
                String menuToken = remoteMessage.getData().get("menuToken");
                String fieldToken = remoteMessage.getData().get("fieldToken");
                String detailField = remoteMessage.getData().get("detailField");
                String value = remoteMessage.getData().get("value");

                Log.d("fcmservice", "detail field: "+ officialId+" menuToken: "+menuToken+" fieldToken: "+fieldToken+" detailField: "+detailField+" Value: "+value);
                RealmHelper.getInstance().updateDetailFieldOfficial(fieldToken, detailField, value);
            }
            if(remoteMessage.getData().get("event").equals("change_menu_detail")){
                String officialId = remoteMessage.getData().get("officialId");
                String menuToken = remoteMessage.getData().get("menuToken");
                String detailMenu = remoteMessage.getData().get("detailMenu");
                String value = remoteMessage.getData().get("value");

                Log.d("fcmservice", "detail menu: "+ officialId+" menuToken: "+menuToken+" detailMenu: "+detailMenu+" Value: "+value);
                RealmHelper.getInstance().updateDetailMenuOfficial(menuToken, detailMenu, value);
            }
            if(remoteMessage.getData().get("event").equals("change_official_detail")){
                String officialId = remoteMessage.getData().get("officialId");
                String detailOfficial = remoteMessage.getData().get("detailOfficial");
                String value = remoteMessage.getData().get("value");

                Log.d("fcmservice", "detail Official: "+ officialId+" detailOfficial: "+detailOfficial+" Value: "+value);
                if(detailOfficial.equals(DBConstants.DESCRIPTION) || detailOfficial.equals(DBConstants.WEBSITE)){
                    RealmHelper.getInstance().updateDetailOfficial(officialId, detailOfficial, value);
                }if(detailOfficial.equals("name") || detailOfficial.equals(DBConstants.DESCRIPTION) || detailOfficial.equals(DBConstants.PHOTO) || detailOfficial.equals(DBConstants.ACCOUNT_TYPE)){
                    RealmHelper.getInstance().updateDetailOfficialUserTable(officialId, detailOfficial, value);
                }
            }
            if(remoteMessage.getData().get("event").equals("change_users_detail")){
                String officialId = remoteMessage.getData().get("officialId");
                String detailUsers = remoteMessage.getData().get("detailUsers");
                String value = remoteMessage.getData().get("value");

                Log.d("fcmservice", "detail users: "+ officialId+" detailUsers: "+detailUsers+" Value: "+value);
                RealmHelper.getInstance().updateDetailUserOfficial(officialId, value);
            }
            if (remoteMessage.getData().get("event").equals("send_field_id")) {
                int state = SharedPreferencesManager.getDeleteFieldState();
                //state 0 buat handle banyak request dari fcm, kembali ke 0 jika sudah selesai
                if (state == 0) {
                    SharedPreferencesManager.setDeleteFieldState(1);
                    String officialId = remoteMessage.getData().get("officialId");
                    Log.d("TEST", "onMessageReceived OFFICIAL NEW: "+officialId);
                    OfficialManager.joinOfficial(officialId, new OfficialManager.OnComplete() {
                        @Override
                        public void onComplete(boolean isSuccess) {
                            SharedPreferencesManager.setDeleteFieldState(0);
                        }
                    }, new OfficialManager.OnComplete() {
                        @Override
                        public void onComplete(boolean isSuccess) {
                            //todo list
                            SharedPreferencesManager.setDeleteFieldState(0);
                        }
                    });
                }
            }
            if (remoteMessage.getData().get("event").equals("push_official_list")) {
                String officialId = remoteMessage.getData().get("officialId");
                RealmHelper.getInstance().clearReflistByOfficialId(officialId);
                Log.d("test", "onMessageReceived: " + officialId);
                OfficialManager.getOfficialList(officialId, FireManager.getUid(), new OfficialManager.OnComplete() {
                    @Override
                    public void onComplete(boolean isSuccess) {

                    }
                });
            }
        } else {

            final String messageId = remoteMessage.getData().get(DBConstants.MESSAGE_ID);

            //if message is deleted do not save it
            if (RealmHelper.getInstance().getDeletedMessage(messageId) != null)
                return;

            boolean isGroup = remoteMessage.getData().containsKey("isGroup");
            //getting data from fcm message and convert it to a message
            final String phone = remoteMessage.getData().get(DBConstants.PHONE);
            final String content = remoteMessage.getData().get(DBConstants.CONTENT);
            final String timestamp = remoteMessage.getData().get(DBConstants.TIMESTAMP);
            final int type = Integer.parseInt(remoteMessage.getData().get(DBConstants.TYPE));
            //get sender uid
            final String fromId = remoteMessage.getData().get(DBConstants.FROM_ID);
            String toId = remoteMessage.getData().get(DBConstants.TOID);
            final String metadata = remoteMessage.getData().get(DBConstants.METADATA);
            //convert sent type to received
            int convertedType = MessageType.convertSentToReceived(type);
            //is official message
            boolean isOfficial = remoteMessage.getData().containsKey("isOfficial");

            Log.i("fcmservice", "onMessageReceived: isofficial " + isOfficial);

            //if it's a group message and the message sender is the same
            if (fromId.equals(FireManager.getUid()))
                return;

            //create the message
            final Message message = new Message();
            message.setContent(content);
            message.setTimestamp(timestamp);
            message.setFromId(fromId);
            message.setType(convertedType);
            message.setMessageId(messageId);
            message.setMetadata(metadata);
            message.setToId(toId);
            message.setChatId(isGroup ? toId : fromId);
            message.setGroup(isGroup);
            if (isGroup)
                message.setFromPhone(phone);
            message.setOfficial(isOfficial);
            //set default state
            message.setDownloadUploadStat(DownloadUploadStat.FAILED);


            //check if it's text message
            if (MessageType.isSentText(type)) {
                //set the state to default
                message.setDownloadUploadStat(DownloadUploadStat.DEFAULT);


                //check if it's a contact
            } else if (remoteMessage.getData().containsKey(DBConstants.CONTACT)) {
                message.setDownloadUploadStat(DownloadUploadStat.DEFAULT);
                //get the json contact as String
                String jsonString = remoteMessage.getData().get(DBConstants.CONTACT);
                //convert contact numbers from JSON to ArrayList
                ArrayList<PhoneNumber> phoneNumbersList = JsonUtil.getPhoneNumbersList(jsonString);
                // convert it to RealmContact and set the contact name using content
                RealmContact realmContact = new RealmContact(content, phoneNumbersList);

                message.setContact(realmContact);


                //check if it's a location message
            } else if (remoteMessage.getData().containsKey(DBConstants.LOCATION)) {
                message.setDownloadUploadStat(DownloadUploadStat.DEFAULT);
                //get the json location as String
                String jsonString = remoteMessage.getData().get(DBConstants.LOCATION);
                //convert location from JSON to RealmLocation
                RealmLocation location = JsonUtil.getRealmLocationFromJson(jsonString);
                message.setLocation(location);
            }

            //check if it's image or Video
            else if (remoteMessage.getData().containsKey(DBConstants.THUMB)) {
                final String thumb = remoteMessage.getData().get(DBConstants.THUMB);

                //Check if it's Video and set Video Duration
                if (remoteMessage.getData().containsKey(DBConstants.MEDIADURATION)) {
                    final String mediaDuration = remoteMessage.getData().get(DBConstants.MEDIADURATION);
                    message.setMediaDuration(mediaDuration);
                }

                message.setThumb(thumb);


                //check if it's Voice Message or Audio File
            } else if (remoteMessage.getData().containsKey(DBConstants.MEDIADURATION)
                    && type == MessageType.SENT_VOICE_MESSAGE || type == MessageType.SENT_AUDIO) {

                //set audio duration
                final String mediaDuration = remoteMessage.getData().get(DBConstants.MEDIADURATION);


                message.setMediaDuration(mediaDuration);

                //check if it's a File
            } else if (remoteMessage.getData().containsKey(DBConstants.FILESIZE)) {
                String fileSize = remoteMessage.getData().get(DBConstants.FILESIZE);


                message.setFileSize(fileSize);

            }

            //if the message was quoted save it and get the quoted message
            if (remoteMessage.getData().containsKey("quotedMessageId")) {
                String quotedMessageId = remoteMessage.getData().get("quotedMessageId");
                //sometimes the message is not saved because of threads,
                //so we need to make sure that we refresh the database before checking if the message is exists
                RealmHelper.getInstance().refresh();
                Message quotedMessage = RealmHelper.getInstance().getMessage(quotedMessageId);
                if (quotedMessage != null)
                    message.setQuotedMessage(QuotedMessage.messageToQuotedMessage(quotedMessage));
            }

            //Save it to database and fire notification
            new NotificationHelper(MyFCMService.this).handleNewMessage(phone, message);


        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        startForeground(NotificationHelper.ID_NOTIFICATION_SERVICE, new NotificationHelper(MyFCMService.this).notifService());
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        stopForeground(true);
        stopSelf();
    }
}