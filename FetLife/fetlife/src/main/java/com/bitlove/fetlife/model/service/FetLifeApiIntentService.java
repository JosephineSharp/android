package com.bitlove.fetlife.model.service;

import android.app.IntentService;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.sqlite.SQLiteReadOnlyDatabaseException;
import android.net.Uri;
import android.preference.PreferenceManager;
import android.util.Log;
import android.webkit.MimeTypeMap;

import com.bitlove.fetlife.BuildConfig;
import com.bitlove.fetlife.FetLifeApplication;
import com.bitlove.fetlife.event.AuthenticationFailedEvent;
import com.bitlove.fetlife.event.FriendRequestSendFailedEvent;
import com.bitlove.fetlife.event.FriendRequestSendSucceededEvent;
import com.bitlove.fetlife.event.LoginFailedEvent;
import com.bitlove.fetlife.event.LoginFinishedEvent;
import com.bitlove.fetlife.event.LoginStartedEvent;
import com.bitlove.fetlife.event.MessageSendFailedEvent;
import com.bitlove.fetlife.event.MessageSendSucceededEvent;
import com.bitlove.fetlife.event.NewConversationEvent;
import com.bitlove.fetlife.event.ServiceCallFailedEvent;
import com.bitlove.fetlife.event.ServiceCallFinishedEvent;
import com.bitlove.fetlife.event.ServiceCallStartedEvent;
import com.bitlove.fetlife.model.api.FetLifeApi;
import com.bitlove.fetlife.model.api.FetLifeService;
import com.bitlove.fetlife.model.db.FetLifeDatabase;
import com.bitlove.fetlife.model.pojos.AuthBody;
import com.bitlove.fetlife.model.pojos.Conversation;
import com.bitlove.fetlife.model.pojos.Conversation_Table;
import com.bitlove.fetlife.model.pojos.Feed;
import com.bitlove.fetlife.model.pojos.Friend;
import com.bitlove.fetlife.model.pojos.FriendRequest;
import com.bitlove.fetlife.model.pojos.FriendRequest_Table;
import com.bitlove.fetlife.model.pojos.Friend_Table;
import com.bitlove.fetlife.model.pojos.Member;
import com.bitlove.fetlife.model.pojos.SharedProfile;
import com.bitlove.fetlife.model.pojos.Message;
import com.bitlove.fetlife.model.pojos.Message_Table;
import com.bitlove.fetlife.model.pojos.SharedProfile_Table;
import com.bitlove.fetlife.model.pojos.Story;
import com.bitlove.fetlife.model.pojos.Token;
import com.bitlove.fetlife.model.pojos.User;
import com.bitlove.fetlife.model.pojos.VideoUploadIdResult;
import com.bitlove.fetlife.util.BytesUtil;
import com.bitlove.fetlife.util.DateUtil;
import com.bitlove.fetlife.util.MessageDuplicationDebugUtil;
import com.bitlove.fetlife.util.NetworkUtil;
import com.crashlytics.android.Crashlytics;
import com.raizlabs.android.dbflow.annotation.Collate;
import com.raizlabs.android.dbflow.config.FlowManager;
import com.raizlabs.android.dbflow.sql.language.OrderBy;
import com.raizlabs.android.dbflow.sql.language.Select;
import com.raizlabs.android.dbflow.structure.InvalidDBConfiguration;
import com.raizlabs.android.dbflow.structure.database.DatabaseWrapper;
import com.raizlabs.android.dbflow.structure.database.transaction.ITransaction;
import com.squareup.okhttp.MediaType;
import com.squareup.okhttp.RequestBody;
import com.squareup.okhttp.ResponseBody;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import retrofit.Call;
import retrofit.Response;

public class FetLifeApiIntentService extends IntentService {

    //****
    //Action names for Api service calls
    //***

    public static final String ACTION_APICALL_MEMBER = "com.bitlove.fetlife.action.apicall.member";
    public static final String ACTION_APICALL_CONVERSATIONS = "com.bitlove.fetlife.action.apicall.cpnversations";
    public static final String ACTION_APICALL_FEED = "com.bitlove.fetlife.action.apicall.feed";
    public static final String ACTION_APICALL_FRIENDS = "com.bitlove.fetlife.action.apicall.friends";
    public static final String ACTION_APICALL_MESSAGES = "com.bitlove.fetlife.action.apicall.messages";
    public static final String ACTION_APICALL_SEND_MESSAGES = "com.bitlove.fetlife.action.apicall.send_messages";
    public static final String ACTION_APICALL_SET_MESSAGES_READ = "com.bitlove.fetlife.action.apicall.set_messages_read";
    public static final String ACTION_APICALL_ADD_LOVE = "com.bitlove.fetlife.action.apicall.add_love";
    public static final String ACTION_APICALL_REMOVE_LOVE = "com.bitlove.fetlife.action.apicall.remove_love";
    public static final String ACTION_APICALL_LOGON_USER = "com.bitlove.fetlife.action.apicall.logon_user";
    public static final String ACTION_APICALL_FRIENDREQUESTS = "com.bitlove.fetlife.action.apicall.friendrequests";
    public static final String ACTION_APICALL_SEND_FRIENDREQUESTS = "com.bitlove.fetlife.action.apicall.send_friendrequests";
    public static final String ACTION_APICALL_UPLOAD_PICTURE = "com.bitlove.fetlife.action.apicall.upload_picture";
    public static final String ACTION_APICALL_UPLOAD_VIDEO = "com.bitlove.fetlife.action.apicall.upload_video";

    //Incoming intent extra parameter name
    private static final String EXTRA_PARAMS = "com.bitlove.fetlife.extra.params";

    //Backend variable for sorting based on updated field
    private static final String PARAM_SORT_ORDER_UPDATED_DESC = "-updated_at";

    //Default limits for retrieving the most recent messages from the backend
    private static final int PARAM_NEWMESSAGE_LIMIT = 50;

    //Default limits for retrieving previous messages from the message history
    private static final int PARAM_OLDMESSAGE_LIMIT = 25;

    //Reference holder for the action that is being processed
    private static final int MAX_SUBJECT_LENGTH = 36;
    private static final String SUBJECT_SHORTENED_SUFFIX = "\u2026";
    private static final String TEXT_PLAIN = "text/plain";

    private static final int PENDING_MESSAGE_RETRY_COUNT = 3;
    private static final int PENDING_FRIENDREQUEST_RETRY_COUNT = 3;

    private static String actionInProgress = null;

    /**
     * Main interaction method with this class to initiate a new Api call with the given parameters
     *
     * @param context The Android context
     * @param action The action matching with the desired Api call
     * @param params Parameters for the Api calls as Strings
     */
    public static synchronized void startApiCall(Context context, String action, String... params) {
        Intent intent = new Intent(context, FetLifeApiIntentService.class);
        intent.setAction(action);
        intent.putExtra(EXTRA_PARAMS, params);
        context.startService(intent);
    }

    public static synchronized void startPendingCalls(Context context) {
        if (!isActionInProgress(ACTION_APICALL_SEND_MESSAGES)) {
            startApiCall(context, ACTION_APICALL_SEND_MESSAGES);
        }
        if (!isActionInProgress(ACTION_APICALL_SEND_FRIENDREQUESTS)) {
            startApiCall(context, ACTION_APICALL_SEND_FRIENDREQUESTS);
        }
    }

    public FetLifeApiIntentService() {
        super("FetLifeApiIntentService");
    }

    //****
    //Methods for geting information about the currently handled request
    //****

    //TODO: think about being more specific and store also parameters for exact identification
    private static synchronized void setActionInProgress(String action) {
        actionInProgress = action;
    }

    public static synchronized String getActionInProgress() {
        return actionInProgress;
    }

    public static synchronized boolean isActionInProgress(String action) {
        if (actionInProgress == null) {
            return false;
        }
        return actionInProgress.equals(action);
    }

    //Main synchronized method (by default by Intent Service implementation) method

    @Override
    protected void onHandleIntent(Intent intent) {

        if (intent == null) {
            return;
        }

        //Set up the Api call related variables
        final String action = intent.getAction();
        String[] params = intent.getStringArrayExtra(EXTRA_PARAMS);

        //Check current logged in user
        //Any communication with the Api is allowed only if the user is logged on, except of course the login process itself
        User currentUser = getFetLifeApplication().getUserSessionManager().getCurrentUser();
        if (currentUser == null && action != ACTION_APICALL_LOGON_USER) {
            return;
        }

        //Check for network state
        if (NetworkUtil.getConnectivityStatus(this) == NetworkUtil.NETWORK_STATUS_NOT_CONNECTED) {
            sendConnectionFailedNotification(action, params);
            return;
        }

        try {

            //Set the current action in progress and notify about whoever is interested
            setActionInProgress(action);
            sendLoadStartedNotification(action);

            //If we do not have any access token (for example because it is expired and removed) try to get new one with the stored refresh token
            if (action != ACTION_APICALL_LOGON_USER && getAccessToken() == null) {
                if (refreshToken(currentUser)) {
                    //If token successfully refreshed restart the original request
                    //Note: this could end up in endless loop if the backend keep sending invalid tokens, but at this point we assume backend works properly from this point of view
                    onHandleIntent(intent);
                } else {
                    //Notify subscribers about failed authentication
                    sendAuthenticationFailedNotification();
                    return;
                }
            }

            //default success result of execution
            int result = Integer.MIN_VALUE;

            //Call the appropriate method based on the action to be executed
            switch (action) {
                case ACTION_APICALL_LOGON_USER:
                    result = logonUser(params);
                    break;
                case ACTION_APICALL_FEED:
                    result = retrieveFeed(params);
                    break;
                case ACTION_APICALL_CONVERSATIONS:
                    result = retrieveConversations(params);
                    break;
                case ACTION_APICALL_FRIENDS:
                    result = retrieveFriends(params);
                    break;
                case ACTION_APICALL_FRIENDREQUESTS:
                    result = retrieveFriendRequests(params);
                    break;
                case ACTION_APICALL_MESSAGES:
                    result = retrieveMessages(currentUser, params);
                    break;
                case ACTION_APICALL_SEND_MESSAGES:
                    for (int i = PENDING_MESSAGE_RETRY_COUNT; i > 0; i--) {
                        result = sendPendingMessages(currentUser, 0);
                        if (result != Integer.MIN_VALUE) {
                            break;
                        }
                    }
                    break;
                case ACTION_APICALL_SET_MESSAGES_READ:
                    result = setMessagesRead(params);
                    break;
                case ACTION_APICALL_ADD_LOVE:
                    result = addLove(params);
                    break;
                case ACTION_APICALL_REMOVE_LOVE:
                    result = removeLove(params);
                    break;
                case ACTION_APICALL_SEND_FRIENDREQUESTS:
                    for (int i = PENDING_FRIENDREQUEST_RETRY_COUNT; i > 0; i--) {
                        result = sendPendingFriendRequests();
                        if (result != Integer.MIN_VALUE) {
                            break;
                        }
                    }
                    break;
                case ACTION_APICALL_UPLOAD_PICTURE:
                    result = uploadPicture(params);
                    break;
                case ACTION_APICALL_UPLOAD_VIDEO:
                    result = uploadVideo(params);
                    break;
                case ACTION_APICALL_MEMBER:
                    result = getMember(params);
                    break;
            }

            int lastResponseCode = getFetLifeApplication().getFetLifeService().getLastResponseCode();

            if (result != Integer.MIN_VALUE) {
                //If the call succeed notify all subscribers about
                sendLoadFinishedNotification(action, result, params);
            } else if (action != ACTION_APICALL_LOGON_USER && (lastResponseCode == 401)) {
                //If the result is failed due to Authentication or Authorization issue, let's try to refresh the token as it is most probably expired
                if (refreshToken(currentUser)) {
                    //If token refresh succeed restart the original request
                    //TODO think about if we can end up endless loop in here in case of not proper response from the backend.
                    onHandleIntent(intent);
                } else {
                    //Notify subscribers about failed authentication
                    sendAuthenticationFailedNotification();
                }
                //TODO: error handling for endless loop
            } else {
                //If the call failed notify all subscribers about
                sendLoadFailedNotification(action, params);
            }
        } catch (IOException ioe) {
            //If the call failed notify all subscribers about
            sendConnectionFailedNotification(action, params);
        } catch (InvalidDBConfiguration|SQLiteReadOnlyDatabaseException|IllegalStateException idb) {
            //db might have been closed due probably to user logout, check it and let
            //the exception go in case of it is not the case
            //TODO: create separate DB Manager class to synchronize db executions and DB close due to user logout
            if (getFetLifeApplication().getUserSessionManager().getCurrentUser() != null) {
                throw idb;
            }
        } finally {
            //make sure we set the action in progress indicator correctly
            setActionInProgress(null);
        }
    }

    //****
    //Authentication related methods / Api calls
    //****

    //Special internal call for refreshing token using refresh token
    private boolean refreshToken(User currentUser) throws IOException {

        if (currentUser == null) {
            return false;
        }

        String refreshToken = currentUser.getRefreshToken();

        if (refreshToken == null) {
            return false;
        }

        Call<Token> tokenRefreshCall = getFetLifeApplication().getFetLifeService().getFetLifeApi().refreshToken(
                BuildConfig.CLIENT_ID,
                BuildConfig.CLIENT_SECRET,
                BuildConfig.REDIRECT_URL,
                FetLifeService.GRANT_TYPE_TOKEN_REFRESH,
                refreshToken
        );

        Response<Token> tokenResponse = tokenRefreshCall.execute();

        if (tokenResponse.isSuccess()) {
            //Set the new token information for the current user and save it to the db
            Token responseBody = tokenResponse.body();
            currentUser.setAccessToken(responseBody.getAccessToken());
            currentUser.setRefreshToken(responseBody.getRefreshToken());
            currentUser.save();
            return true;
        } else {
            return false;
        }
    }


    //Call for logging in the user
    private int logonUser(String... params) throws IOException {
        Call<Token> tokenCall = getFetLifeApplication().getFetLifeService().getFetLifeApi().login(
                BuildConfig.CLIENT_ID,
                BuildConfig.CLIENT_SECRET,
                BuildConfig.REDIRECT_URL,
                new AuthBody(params[0], params[1]));

        Response<Token> tokenResponse = tokenCall.execute();
        if (tokenResponse.isSuccess()) {
            Token responseBody = tokenResponse.body();
            String accessToken = responseBody.getAccessToken();
            //Retrieve user information from the backend after Authentication
            User user = retrieveCurrentUser(accessToken);
            if (user == null) {
                return Integer.MIN_VALUE;
            }
            //Save the user information with the tokens into the backend
            user.setAccessToken(accessToken);
            user.setRefreshToken(responseBody.getRefreshToken());

            //Notify the Session Manager about finished logon process
            getFetLifeApplication().getUserSessionManager().onUserLogIn(user, getBoolFromParams(params, 2, true));
            return 1;
        } else {
            return Integer.MIN_VALUE;
        }
    }

    //Special internal call to retrieve user information after authentication
    private User retrieveCurrentUser(String accessToken) throws IOException {
        Call<User> getMeCall = getFetLifeApi().getMe(FetLifeService.AUTH_HEADER_PREFIX + accessToken);
        Response<User> getMeResponse = getMeCall.execute();
        if (getMeResponse.isSuccess()) {
            return getMeResponse.body();
        } else {
            return null;
        }
    }


    //****
    //Pending post request related methods / Api calls
    //****

    //Go through all the pending messages and send them one by one
    private int sendPendingMessages(User user, int sentMessageCount) throws IOException {
        List<Message> pendingMessages = new Select().from(Message.class).where(Message_Table.pending.is(true)).orderBy(Message_Table.date,true).queryList();
        //Go through all pending messages (if there is any) and try to send them
        for (Message pendingMessage : pendingMessages) {
            String conversationId = pendingMessage.getConversationId();
            //If the conversation id is local (not created by the backend) it's a new conversation, so start a new conversation call
            if (Conversation.isLocal(conversationId)) {
                if (startNewConversation(user, conversationId, pendingMessage)) {
                    //db changed, reload remaining pending messages with starting this method recursively
                    return sendPendingMessages(user, ++sentMessageCount);
                } else {
                    return Integer.MIN_VALUE;
                }
            } else if (sendPendingMessage(pendingMessage)) {
                MessageDuplicationDebugUtil.checkSentMessage(pendingMessage);
                sentMessageCount++;
            } else {
                return Integer.MIN_VALUE;
            }
        }
        //Return success result if at least one pending message could have been sent so there was a change in the current state
        return sentMessageCount;
    }

    private boolean startNewConversation(User user, String localConversationId, Message startMessage) throws IOException {

        Conversation pendingConversation = new Select().from(Conversation.class).where(Conversation_Table.id.is(localConversationId)).querySingle();
        if (pendingConversation == null) {
            return false;
        }

        String body = startMessage.getBody();
        String subject = body == null || body.length() <= MAX_SUBJECT_LENGTH ? body : body.substring(0,MAX_SUBJECT_LENGTH).concat(SUBJECT_SHORTENED_SUFFIX);
        Call<Conversation> postConversationCall = getFetLifeApi().postConversation(FetLifeService.AUTH_HEADER_PREFIX + getAccessToken(), pendingConversation.getMemberId(), subject, body);
        Response<Conversation> postConversationResponse = postConversationCall.execute();
        if (postConversationResponse.isSuccess()) {
            //Delete the local conversation and create a new one, as the id of the conversation is changed (from local to backend based)
            pendingConversation.delete();

            Conversation conversation = postConversationResponse.body();
            conversation.save();

            String serverConversationId = conversation.getId();

            //Delete the temporary local start messages as it is now accessible via a backend call with its real backend related id
            //This will ensure we wont have any duplication
            startMessage.delete();
            //Retrieve the init message fot the conversation with the real backend id
            retrieveMessages(user, serverConversationId);

            //Update all other messages the user initiated in the meanwhile so they are not mapped to the new conversation
            //They will be now pending messages ready to be sent so a next scan (will be forced after returning from this method) will find them and send them
            List<Message> pendingMessages = new Select().from(Message.class).where(Message_Table.conversationId.is(localConversationId)).queryList();
            for (Message pendingMessage : pendingMessages) {
                pendingMessage.setConversationId(serverConversationId);
                pendingMessage.save();
            }

            //Notify subscribers about new conversation event
            getFetLifeApplication().getEventBus().post(new NewConversationEvent(localConversationId, serverConversationId));
            return true;
        } else {
            return false;
        }
    }

    //Send one particular message

    private static long lastSentMessageTime;

    private boolean sendPendingMessage(Message pendingMessage) throws IOException {
        //Server can handle only one message in every second without adding the same date to it

        //Workaround for server issue as it does not handle millis
        while (System.currentTimeMillis()/1000 == lastSentMessageTime/1000) {}

        String dateString = DateUtil.toServerString(System.currentTimeMillis());
        Call<Message> postMessagesCall = getFetLifeApi().postMessage(FetLifeService.AUTH_HEADER_PREFIX + getAccessToken(), pendingMessage.getConversationId(), pendingMessage.getBody(), dateString);
        Response<Message> postMessageResponse = postMessagesCall.execute();

        String conversationId = pendingMessage.getConversationId();
        if (postMessageResponse.isSuccess()) {
            //Update the message state of the returned message object
            final Message message = postMessageResponse.body();

            lastSentMessageTime = message.getDate();

            //Messages are identified in the db by client id so original pending message will be overridden here with the correct state
            message.setClientId(pendingMessage.getClientId());
            message.setPending(false);
            message.setConversationId(conversationId);
            message.update();
            getFetLifeApplication().getEventBus().post(new MessageSendSucceededEvent(conversationId));
            return true;
        } else {
            //If the call failed make the pending message to a failed message
            //Note if the post is failed due to connection issue an exception will be thrown, so here we make the assumption the failure is permanent.
            //TODO check the result code and based on that send the message permamntly failed or keep it still pending
            //TODO add functionality for the user to be able to retry sending failed messages
            pendingMessage.setPending(false);
            pendingMessage.setFailed(true);
            pendingMessage.save();
            getFetLifeApplication().getEventBus().post(new MessageSendFailedEvent(conversationId));
            return false;
        }
    }

    private int sendPendingFriendRequests() throws IOException {

        int sentCount = 0;
        List<FriendRequest> pendingFriendRequests = new Select().from(FriendRequest.class).where(FriendRequest_Table.pending.is(true)).queryList();
        for (FriendRequest pendingFriendRequest : pendingFriendRequests) {
            if (!sendPendingFriendRequest(pendingFriendRequest)) {
                pendingFriendRequest.delete();
            } else {
                sentCount++;
            }
        }
        List<SharedProfile> pendingSharedProfiles = new Select().from(SharedProfile.class).where(SharedProfile_Table.pending.is(true)).queryList();
        for (SharedProfile pendingSharedProfile : pendingSharedProfiles) {
            if (!sendPendingSharedProfile(pendingSharedProfile)) {
                pendingSharedProfile.delete();
            } else {
                sentCount++;
            }
        }
        //TODO: check later if sending error counter would make any sense here
        return sentCount;
    }

    private boolean sendPendingSharedProfile(SharedProfile pendingSharedProfile) throws IOException {
        Call<FriendRequest> createFriendRequestCall = getFetLifeApi().createFriendRequest(FetLifeService.AUTH_HEADER_PREFIX + getAccessToken(), pendingSharedProfile.getId());
        Response<FriendRequest> friendRequestResponse = createFriendRequestCall.execute();
        if (friendRequestResponse.isSuccess()) {
            pendingSharedProfile.delete();
            getFetLifeApplication().getEventBus().post(new FriendRequestSendSucceededEvent());
            return true;
        } else {
            getFetLifeApplication().getEventBus().post(new FriendRequestSendFailedEvent());
            return false;
        }
    }

    private boolean sendPendingFriendRequest(FriendRequest pendingFriendRequest) throws IOException {
        Call<FriendRequest> friendRequestsCall;
        switch (pendingFriendRequest.getPendingState()) {
            case ACCEPTED:
                friendRequestsCall = getFetLifeApi().acceptFriendRequests(FetLifeService.AUTH_HEADER_PREFIX + getAccessToken(), pendingFriendRequest.getId());
                break;
            case REJECTED:
                friendRequestsCall = getFetLifeApi().removeFriendRequests(FetLifeService.AUTH_HEADER_PREFIX + getAccessToken(), pendingFriendRequest.getId());
                break;
            default:
                return false;
        }

        Response<FriendRequest> friendRequestResponse = friendRequestsCall.execute();
        if (friendRequestResponse.isSuccess()) {
            pendingFriendRequest.delete();
            getFetLifeApplication().getEventBus().post(new FriendRequestSendSucceededEvent());
            return true;
        } else {
            pendingFriendRequest.delete();
            getFetLifeApplication().getEventBus().post(new FriendRequestSendFailedEvent());
            return false;
        }
    }


    //****
    //Other not pending state based POST methods
    //****
    //TODO make these also pending in case of connection failed and retry later

    private int setMessagesRead(String[] params) throws IOException {
        String conversationId = params[0];
        String[] messageIds = Arrays.copyOfRange(params, 1, params.length);
        Call<ResponseBody> setMessagesReadCall = getFetLifeApi().setMessagesRead(FetLifeService.AUTH_HEADER_PREFIX + getAccessToken(), conversationId, messageIds);
        Response<ResponseBody> response = setMessagesReadCall.execute();
        return response.isSuccess() ? 1 : Integer.MIN_VALUE;
    }

    private int addLove(String[] params) throws IOException {
        String contentId = params[0];
        String contentType = params[1];
        Call<ResponseBody> addLoveCall = getFetLifeApi().putLove(FetLifeService.AUTH_HEADER_PREFIX + getAccessToken(), contentId, contentType);
        Response<ResponseBody> response = addLoveCall.execute();
        return response.isSuccess() ? 1 : Integer.MIN_VALUE;
    }

    private int removeLove(String[] params) throws IOException {
        String contentId = params[0];
        String contentType = params[1];
        Call<ResponseBody> removeLoveCall = getFetLifeApi().deleteLove(FetLifeService.AUTH_HEADER_PREFIX + getAccessToken(), contentId, contentType);
        Response<ResponseBody> response = removeLoveCall.execute();
        return response.isSuccess() ? 1 : Integer.MIN_VALUE;
    }

    //****
    //Multimedia (POST) related methods / Api calls
    //****

    private int uploadPicture(String[] params) throws IOException {

        Uri uri = Uri.parse(params[0]);
        ContentResolver contentResolver = getFetLifeApplication().getContentResolver();

        boolean deleteAfterUpload = getBoolFromParams(params, 1, false);
        String caption = params[2];
        boolean friendsOnly = getBoolFromParams(params, 3, false);

        InputStream inputStream;
        String mimeType = getMimeType(uri, false, contentResolver);

        if (mimeType == null) {
            Crashlytics.logException(new Exception("Media type for file to upload not found"));
            return Integer.MIN_VALUE;
        }

        try {
            inputStream = contentResolver.openInputStream(uri);
        } catch (Exception e) {
            Crashlytics.logException(new Exception("Media file to upload not found", e));
            return Integer.MIN_VALUE;
        }

        RequestBody mediaBody = RequestBody.create(MediaType.parse(mimeType), BytesUtil.getBytes(inputStream));
        RequestBody isAvatarPart = RequestBody.create(MediaType.parse(TEXT_PLAIN), Boolean.toString(false));
        RequestBody friendsOnlyPart = RequestBody.create(MediaType.parse(TEXT_PLAIN), Boolean.toString(friendsOnly));
        RequestBody captionPart = RequestBody.create(MediaType.parse(TEXT_PLAIN), caption);
        RequestBody isFromUserPart = RequestBody.create(MediaType.parse(TEXT_PLAIN), Boolean.toString(true));

        Call<ResponseBody> uploadPictureCall = getFetLifeApi().uploadPicture(FetLifeService.AUTH_HEADER_PREFIX + getAccessToken(), mediaBody, isAvatarPart, friendsOnlyPart, captionPart, isFromUserPart);
        Response<ResponseBody> response = uploadPictureCall.execute();

        if (deleteAfterUpload) {
            try {
                getContentResolver().delete(uri, null, null);
            } catch (IllegalArgumentException iae) {
                File contentFile = new File(uri.toString());
                if (contentFile.exists()) {
                    contentFile.delete();
                }
            }
        }

        return response.isSuccess() ? 1 : Integer.MIN_VALUE;
    }

    private int uploadVideo(String[] params) throws IOException {

        Uri uri = Uri.parse(params[0]);
        ContentResolver contentResolver = getFetLifeApplication().getContentResolver();

        boolean deleteAfterUpload = getBoolFromParams(params, 1, false);
        String caption = params[2];
        boolean friendsOnly = getBoolFromParams(params, 3, false);

        InputStream inputStream;
        String mimeType = getMimeType(uri, true, contentResolver);

        if (mimeType == null) {
            Crashlytics.logException(new Exception("Media type for file to upload not found"));
            return Integer.MIN_VALUE;
        }

        Call<VideoUploadIdResult> uploadVideoStartCall = getFetLifeApi().uploadVideoStart(FetLifeService.AUTH_HEADER_PREFIX + getAccessToken(), caption, caption, "video.jpeg", true, true);
        Response<VideoUploadIdResult> uploadVideoStartResponse = uploadVideoStartCall.execute();

        if (!uploadVideoStartResponse.isSuccess()) {
            return Integer.MIN_VALUE;
        }

        String videoUploadId = uploadVideoStartResponse.body().getVideoUploadId();

        try {
            inputStream = contentResolver.openInputStream(uri);
        } catch (Exception e) {
            Crashlytics.logException(new Exception("Media file to upload not found", e));
            return Integer.MIN_VALUE;
        }

        RequestBody mediaBody = RequestBody.create(MediaType.parse(mimeType), BytesUtil.getBytes(inputStream));
        RequestBody numberPart = RequestBody.create(MediaType.parse(TEXT_PLAIN), Integer.toString(1));

        Call<ResponseBody> uploadVideoPartCall = getFetLifeApi().uploadVideoPart(FetLifeService.AUTH_HEADER_PREFIX + getAccessToken(), videoUploadId, mediaBody, numberPart);
        Response<ResponseBody> uploadVideoPartResponse = uploadVideoPartCall.execute();

        if (deleteAfterUpload) {
            try {
                getContentResolver().delete(uri, null, null);
            } catch (IllegalArgumentException iae) {
                File contentFile = new File(uri.toString());
                if (contentFile.exists()) {
                    contentFile.delete();
                }
            }
        }

        if (!uploadVideoPartResponse.isSuccess()) {
            return Integer.MIN_VALUE;
        }

        Call<ResponseBody> uploadVideoFinishCall = getFetLifeApi().uploadVideoFinish(FetLifeService.AUTH_HEADER_PREFIX + getAccessToken(), videoUploadId);
        Response<ResponseBody> uploadVideoFinishResponse = uploadVideoFinishCall.execute();

        return uploadVideoFinishResponse.isSuccess() ? 1 : Integer.MIN_VALUE;
    }

    public static String getMimeType(Uri uri, boolean isVideo, ContentResolver contentResolver) {
        String mimeType;

        //Check uri format to avoid null
        if (uri.getScheme().equals(ContentResolver.SCHEME_CONTENT)) {
            //If scheme is a content
            mimeType = contentResolver.getType(uri);
        } else {
            //If scheme is a File
            //This will replace white spaces with %20 and also other special characters. This will avoid returning null values on file name with spaces and special characters.
            String extension = MimeTypeMap.getFileExtensionFromUrl(Uri.fromFile(new File(uri.getPath())).toString());
            mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);

        }

        if (mimeType == null || mimeType.trim().length() == 0) {
            //let's give a try
            Crashlytics.logException(new Exception("MimeType could not be read for image upload, falling back to image/jpeg"));
            mimeType = isVideo ? "video/mp4" : "image/jpeg";
        }

        return mimeType;
    }

    //****
    //Retrieve (GET) related methods / Api calls
    //****

    private int retrieveMessages(User user, String... params) throws IOException {
        final String conversationId = params[0];

        final boolean loadNewMessages = getBoolFromParams(params, 1, true);

        Call<Conversation> getConversationCall = getFetLifeApi().getConversation(FetLifeService.AUTH_HEADER_PREFIX + getAccessToken(), conversationId);
        Response<Conversation> conversationResponse = getConversationCall.execute();

        if (conversationResponse.isSuccess()) {
            Conversation retrievedConversation = conversationResponse.body();
            Conversation localConversation = new Select().from(Conversation.class).where(Conversation_Table.id.is(conversationId)).querySingle();
            if (localConversation !=null) {
                retrievedConversation.setDraftMessage(localConversation.getDraftMessage());
            }
            retrievedConversation.save();
        } else {
            return Integer.MIN_VALUE;
        }

        Call<List<Message>> getMessagesCall = null;
        if (loadNewMessages) {
            String selfId = user.getId();
            Message newestMessage = new Select().from(Message.class).where(Message_Table.conversationId.is(conversationId)).and(Message_Table.senderId.isNot(selfId)).orderBy(Message_Table.date, false).querySingle();
            getMessagesCall = getFetLifeApi().getMessages(FetLifeService.AUTH_HEADER_PREFIX + getAccessToken(), conversationId, newestMessage != null ? newestMessage.getId() : null, null, PARAM_NEWMESSAGE_LIMIT);
        } else {
            Message oldestMessage = new Select().from(Message.class).where(Message_Table.conversationId.is(conversationId)).and(Message_Table.pending.is(false)).orderBy(Message_Table.date,true).querySingle();
            getMessagesCall = getFetLifeApi().getMessages(FetLifeService.AUTH_HEADER_PREFIX + getAccessToken(), conversationId, null, oldestMessage != null ? oldestMessage.getId() : null, PARAM_OLDMESSAGE_LIMIT);
        }

        //TODO solve edge case when there is the gap between last message in db and the retrieved messages (e.g. when because of the limit not all recent messages could be retrieved)

        Response<List<Message>> messagesResponse = getMessagesCall.execute();
        if (messagesResponse.isSuccess()) {
            final List<Message> messages = messagesResponse.body();
            FlowManager.getDatabase(FetLifeDatabase.class).executeTransaction(new ITransaction() {
                @Override
                public void execute(DatabaseWrapper databaseWrapper) {
                    for (Message message : messages) {
                        Message storedMessage = new Select().from(Message.class).where(Message_Table.id.is(message.getId())).querySingle();
                        if (storedMessage != null) {
                            message.setClientId(storedMessage.getClientId());
                        } else {
                            message.setClientId(UUID.randomUUID().toString());
                        }
                        message.setConversationId(conversationId);
                        message.setPending(false);
                        message.save();
                    }
                }
            });
            return messages.size();
        } else {
            return Integer.MIN_VALUE;
        }
    }

    private int retrieveFeed(String[] params) throws IOException {
        final int limit = getIntFromParams(params, 0, 25);
        final int page = getIntFromParams(params, 1, 1);

        Call<Feed> getFeedCall = getFetLifeApi().getFeed(FetLifeService.AUTH_HEADER_PREFIX + getAccessToken(), limit, page);
//        Call<Feed> getFeedCall = getFetLifeApi().getFeed2(FetLifeService.AUTH_HEADER_PREFIX + getAccessToken());
        Response<Feed> feedResponse = getFeedCall.execute();
        if (feedResponse.isSuccess()) {
            final Feed feed = feedResponse.body();
            final List<Story> stories = feed.getStories();
            getFetLifeApplication().getInMemoryStorage().addFeed(page,stories);

//            Kept for later if/when we move to database persistence
//            FlowManager.getDatabase(FetLifeDatabase.class).executeTransaction(new ITransaction() {
//                @Override
//                public void execute(DatabaseWrapper databaseWrapper) {
//                    for (FeedStory story : stories) {
//                        story.save();
//                    }
//                }
//            });

            return stories.size();
        } else {
            return Integer.MIN_VALUE;
        }
    }

    private int retrieveConversations(String[] params) throws IOException {
        final int limit = getIntFromParams(params, 0, 25);
        final int page = getIntFromParams(params, 1, 1);

        Call<List<Conversation>> getConversationsCall = getFetLifeApi().getConversations(FetLifeService.AUTH_HEADER_PREFIX + getAccessToken(), PARAM_SORT_ORDER_UPDATED_DESC, limit, page);
        Response<List<Conversation>> conversationsResponse = getConversationsCall.execute();
        if (conversationsResponse.isSuccess()) {
            final List<Conversation> conversations = conversationsResponse.body();
            final List<Conversation> currentConversations = new Select().from(Conversation.class).orderBy(Conversation_Table.date,false).queryList();

            int lastConfirmedConversationPosition;
            if (page == 1) {
                lastConfirmedConversationPosition = -1;
            } else {
                lastConfirmedConversationPosition = loadLastSyncedPosition(SyncedPositionType.CONVERSATION);
            }
            int newItemCount = 0, deletedItemCount = 0;

            for (Conversation conversation : conversations) {
                int foundPos;
                for (foundPos = lastConfirmedConversationPosition+1; foundPos < currentConversations.size(); foundPos++) {
                    Conversation checkConversation = currentConversations.get(foundPos);
                    if (conversation.getId().equals(checkConversation.getId())) {
                        conversation.setDraftMessage(checkConversation.getDraftMessage());
                        break;
                    }
                }
                if (foundPos >= currentConversations.size()) {
                    newItemCount++;
                } else {
                    for (int i = lastConfirmedConversationPosition+1; i < foundPos; i++) {
                        Conversation notMatchedConversation = currentConversations.get(i);
                        if (Conversation.isUnanswered(notMatchedConversation.getId(),getFetLifeApplication())) {
                            lastConfirmedConversationPosition++;
                        } else {
                            notMatchedConversation.delete();
                            deletedItemCount++;
                        }
                    }
                    lastConfirmedConversationPosition = foundPos;
                }
                conversation.save();
            }

            saveLastSyncedPosition(SyncedPositionType.CONVERSATION,lastConfirmedConversationPosition+newItemCount);

            return conversations.size() - deletedItemCount;
        } else {
            return Integer.MIN_VALUE;
        }
    }

    private String getAccessToken() {
        User currentUser = getFetLifeApplication().getUserSessionManager().getCurrentUser();
        if (currentUser == null) {
            return null;
        }
        return currentUser.getAccessToken();
    }

    private int getMember(String... params) throws IOException {
        Call<Member> getMemberCall = getFetLifeApi().getMember(FetLifeService.AUTH_HEADER_PREFIX + getAccessToken(), params[0]);
        Response<Member> getMemberResponse = getMemberCall.execute();
        if (getMemberResponse.isSuccess()) {
            getMemberResponse.body().save();
            return 1;
        } else {
            return Integer.MIN_VALUE;
        }
    }

    private int retrieveFriends(String[] params) throws IOException {
        final int limit = getIntFromParams(params, 0, 10);
        final int page = getIntFromParams(params, 1, 1);

        Call<List<Friend>> getFriendsCall = getFetLifeApi().getFriends(FetLifeService.AUTH_HEADER_PREFIX + getAccessToken(), limit, page);
        Response<List<Friend>> friendsResponse = getFriendsCall.execute();
        if (friendsResponse.isSuccess()) {

            final List<Friend> friends = friendsResponse.body();
            List<Friend> currentFriends = new Select().from(Friend.class).orderBy(OrderBy.fromProperty(Friend_Table.nickname).ascending().collate(Collate.NOCASE)).queryList();

            int lastConfirmedFriendPosition;
            if (page == 1) {
                lastConfirmedFriendPosition = -1;
            } else {
                lastConfirmedFriendPosition = loadLastSyncedPosition(SyncedPositionType.FRIEND);
            }
            int newItemCount = 0, deletedItemCount = 0;

            for (Friend friend : friends) {
                int foundPos;
                for (foundPos = lastConfirmedFriendPosition+1; foundPos < currentFriends.size(); foundPos++) {
                    Friend checkFriend = currentFriends.get(foundPos);
                    if (friend.getId().equals(checkFriend.getId())) {
                        break;
                    }
                }
                if (foundPos >= currentFriends.size()) {
                    newItemCount++;
                } else {
                    for (int i = lastConfirmedFriendPosition+1; i < foundPos; i++) {
                        currentFriends.get(i).delete();
                        deletedItemCount++;
                    }
                    lastConfirmedFriendPosition = foundPos;
                }
                friend.save();
            }

            saveLastSyncedPosition(SyncedPositionType.FRIEND,lastConfirmedFriendPosition+newItemCount);

            return friends.size() - deletedItemCount;
        } else {
            return Integer.MIN_VALUE;
        }
    }

    private int retrieveFriendRequests(String[] params) throws IOException {
        final int limit = getIntFromParams(params, 0, 10);
        final int page = getIntFromParams(params, 1, 1);

        Call<List<FriendRequest>> getFriendRequestsCall = getFetLifeApi().getFriendRequests(FetLifeService.AUTH_HEADER_PREFIX + getAccessToken(), limit, page);
        Response<List<FriendRequest>> friendRequestsResponse = getFriendRequestsCall.execute();
        if (friendRequestsResponse.isSuccess()) {
            final List<FriendRequest> friendRequests = friendRequestsResponse.body();
            FlowManager.getDatabase(FetLifeDatabase.class).executeTransaction(new ITransaction() {
                @Override
                public void execute(DatabaseWrapper databaseWrapper) {
                    for (FriendRequest friendRequest : friendRequests) {
                        FriendRequest storedFriendRequest = new Select().from(FriendRequest.class).where(FriendRequest_Table.id.is(friendRequest.getId())).querySingle();
                        if (storedFriendRequest != null) {
                            //skip
                        } else {
                            friendRequest.setClientId(UUID.randomUUID().toString());
                            friendRequest.save();
                        }
                    }
                }
            });
            return friendRequests.size();
        } else {
            return Integer.MIN_VALUE;
        }
    }


    //****
    //Notification sending methods
    //****

    private void sendAuthenticationFailedNotification() {
        getFetLifeApplication().getEventBus().post(new AuthenticationFailedEvent());
    }

    private void sendLoadStartedNotification(String action) {
        switch (action) {
            case ACTION_APICALL_LOGON_USER:
                getFetLifeApplication().getEventBus().post(new LoginStartedEvent());
                break;
            default:
                getFetLifeApplication().getEventBus().post(new ServiceCallStartedEvent(action));
                break;
        }
    }

    private void sendLoadFinishedNotification(String action, int count, String... params) {
        switch (action) {
            case ACTION_APICALL_LOGON_USER:
                getFetLifeApplication().getEventBus().post(new LoginFinishedEvent());
                break;
            default:
                getFetLifeApplication().getEventBus().post(new ServiceCallFinishedEvent(action, count, params));
                break;
        }
    }

    private void sendLoadFailedNotification(String action, String... params) {
        switch (action) {
            case ACTION_APICALL_LOGON_USER:
                getFetLifeApplication().getEventBus().post(new LoginFailedEvent());
                break;
            default:
                getFetLifeApplication().getEventBus().post(new ServiceCallFailedEvent(action, false, params));
                break;
        }
    }

    private void sendConnectionFailedNotification(String action, String... params) {
        switch (action) {
            case ACTION_APICALL_LOGON_USER:
                getFetLifeApplication().getEventBus().post(new LoginFailedEvent(true));
                break;
            default:
                getFetLifeApplication().getEventBus().post(new ServiceCallFailedEvent(action, true, params));
                break;
        }
    }


    //****
    //Helper access methods to retrieve references from other holders
    //****

    private FetLifeApplication getFetLifeApplication() {
        return (FetLifeApplication) getApplication();
    }

    private FetLifeApi getFetLifeApi() {
        return getFetLifeApplication().getFetLifeService().getFetLifeApi();
    }


    //****
    //Helper utility methods
    //****

    private int getIntFromParams(String[] params, int pageParamPosition, int defaultValue) {
        int param = defaultValue;
        if (params != null && params.length > pageParamPosition) {
            try {
                param = Integer.parseInt(params[pageParamPosition]);
            } catch (NumberFormatException nfe) {
            }
        }
        return param;
    }

    private boolean getBoolFromParams(String[] params, int pageParamPosition, boolean defaultValue) {
        boolean param = defaultValue;
        if (params != null && params.length > pageParamPosition) {
            try {
                param = Boolean.parseBoolean(params[pageParamPosition]);
            } catch (NumberFormatException nfe) {
            }
        }
        return param;
    }

    private enum SyncedPositionType {
        FRIEND,
        CONVERSATION;

        @Override
        public String toString() {
            return getClass().getSimpleName() + "." + super.toString();
        }
    }

    private void saveLastSyncedPosition(SyncedPositionType syncedPositionType, int position) {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(getFetLifeApplication());
        preferences.edit().putInt(syncedPositionType.toString(), position).apply();
    }

    private int loadLastSyncedPosition(SyncedPositionType syncedPositionType) {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(getFetLifeApplication());
        return preferences.getInt(syncedPositionType.toString(), -1);
    }

}
