package com.bitlove.fetlife.view.activity.resource;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.view.View;

import com.bitlove.fetlife.FetLifeApplication;
import com.bitlove.fetlife.R;
import com.bitlove.fetlife.event.NewMessageEvent;
import com.bitlove.fetlife.model.pojos.Conversation;
import com.bitlove.fetlife.model.service.FetLifeApiIntentService;
import com.bitlove.fetlife.view.activity.component.MenuActivityComponent;
import com.bitlove.fetlife.view.adapter.ConversationsRecyclerAdapter;
import com.bitlove.fetlife.view.adapter.ResourceListRecyclerAdapter;

import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

public class ConversationsActivity extends ResourceListActivity<Conversation> implements MenuActivityComponent.MenuActivityCallBack {

    public static void startActivity(Context context, boolean newTask) {
        context.startActivity(createIntent(context, newTask));
    }

    public static Intent createIntent(Context context, boolean newTask) {
        Intent intent = new Intent(context, ConversationsActivity.class);
        if (!newTask && FetLifeApplication.getInstance().getUserSessionManager().getActiveUserPreferences().getBoolean(context.getString(R.string.settings_key_general_feed_as_start),false)) {
            intent.setFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        } else {
            intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        }
        return intent;
    }

    @Override
    protected void onCreateActivityComponents() {
        addActivityComponent(new MenuActivityComponent());
    }

    @Override
    protected void onResourceCreate(Bundle savedInstanceState) {
        super.onResourceCreate(savedInstanceState);

        floatingActionButton.setVisibility(View.VISIBLE);
        floatingActionButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                FriendsActivity.startActivity(ConversationsActivity.this, FriendsActivity.FriendListMode.NEW_CONVERSATION);
            }
        });
        floatingActionButton.setContentDescription(getString(R.string.button_new_conversation_discription));
    }

    @Override
    protected ResourceListRecyclerAdapter<Conversation, ?> createRecyclerAdapter(Bundle savedInstanceState) {
        return new ConversationsRecyclerAdapter();
    }

    @Override
    protected String getApiCallAction() {
        return FetLifeApiIntentService.ACTION_APICALL_CONVERSATIONS;
    }

    @Override
    public void onItemClick(Conversation conversation) {
        MessagesActivity.startActivity(ConversationsActivity.this, conversation.getId(), conversation.getNickname(), conversation.getAvatarLink(), false);
    }

    @Override
    public void onAvatarClick(Conversation conversation) {
        String url = conversation.getMemberLink();
        if (url != null) {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setData(Uri.parse(url));
            startActivity(intent);
        }
    }

    @Override
    public boolean finishAtMenuNavigation() {
        return getFetLifeApplication().getUserSessionManager().getActiveUserPreferences().getBoolean(getString(R.string.settings_key_general_feed_as_start),false);
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onNewMessageArrived(NewMessageEvent newMessageEvent) {
        showProgress();
        if (!FetLifeApiIntentService.isActionInProgress(FetLifeApiIntentService.ACTION_APICALL_CONVERSATIONS)) {
            FetLifeApiIntentService.startApiCall(this, FetLifeApiIntentService.ACTION_APICALL_CONVERSATIONS);
        }
    }

}
