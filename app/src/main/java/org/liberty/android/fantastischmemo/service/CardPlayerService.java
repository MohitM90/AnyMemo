/*
Copyright (C) 2013 Haowen Ning

This program is free software; you can redistribute it and/or
modify it under the terms of the GNU General Public License
as published by the Free Software Foundation; either version 2
of the License, or (at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.

See the GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program; if not, write to the Free Software
Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.

*/
package org.liberty.android.fantastischmemo.service;

import android.app.PendingIntent;
import android.content.Intent;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import androidx.core.app.NotificationCompat;
import android.util.Log;

import com.google.common.base.Preconditions;

import org.liberty.android.fantastischmemo.R;
import org.liberty.android.fantastischmemo.common.AnyMemoDBOpenHelper;
import org.liberty.android.fantastischmemo.common.AnyMemoDBOpenHelperManager;
import org.liberty.android.fantastischmemo.common.BaseService;
import org.liberty.android.fantastischmemo.entity.Card;
import org.liberty.android.fantastischmemo.entity.Option;
import org.liberty.android.fantastischmemo.service.cardplayer.CardPlayerContext;
import org.liberty.android.fantastischmemo.service.cardplayer.CardPlayerEventHandler;
import org.liberty.android.fantastischmemo.service.cardplayer.CardPlayerMessage;
import org.liberty.android.fantastischmemo.ui.CardPlayerActivity;
import org.liberty.android.fantastischmemo.utils.CardTTSUtil;

import javax.inject.Inject;

public class CardPlayerService extends BaseService {
    public static final String EXTRA_DBPATH = "dbpath";

    public static final String EXTRA_CURRENT_CARD_ID = "current_card_id";

    public static final String ACTION_GO_TO_CARD = "org.liberty.android.fantastischmemo.CardPlayerService.ACTION_GO_TO_CARD";

    public static final String ACTION_PLAYING_STOPPED = "org.liberty.android.fantastischmemo.CardPlayerService.PLAYING_STOPPED";

    private static final String TAG = CardPlayerService.class.getSimpleName();

    // Magic id used for Card player's notification
    private static final int NOTIFICATION_ID = 9283372;

    // This is the object that receives interactions from clients.
    private final IBinder binder = new LocalBinder();

    private String dbPath;

    private AnyMemoDBOpenHelper dbOpenHelper;

    private Handler handler;

    private CardTTSUtil cardTTSUtil;

    @Inject Option option;

    // The context used for card player state machine.
    private volatile CardPlayerContext cardPlayerContext = null;

    @Override
    public void onCreate() {
        super.onCreate();
        appComponents().inject(this);
    }

    // Note, it is recommended for service binding in a thread different
    // from UI thread. The initialization like DAO creation is quite heavy
    @Override
    public IBinder onBind(Intent intent) {
        handler = new Handler();
        Bundle extras = intent.getExtras();

        assert extras != null : "dbpath is not passed to AMTTSService.";

        dbPath = extras.getString(EXTRA_DBPATH);

        final int cardId = extras.getInt(EXTRA_CURRENT_CARD_ID);
        
        cardTTSUtil = new CardTTSUtil(getApplicationContext(), dbPath);

        dbOpenHelper = AnyMemoDBOpenHelperManager.getHelper(this, dbPath);

        // Assign a value to the cardPlayerContext so we do not need to check
        // null for every player methods. The initial STOPPED state will help
        // skipToPrev/skipToNext method to callback the event handler.
        reset();
        cardPlayerContext.setCurrentCard(dbOpenHelper.getCardDao().queryForId(cardId));

        return binder;
    }

    @Override
    public void onRebind(Intent intent) {
        super.onRebind(intent);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // We want this service to continue running until it is explicitly
        // stopped, so return sticky.
        return START_STICKY;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        AnyMemoDBOpenHelperManager.releaseHelper(dbOpenHelper);
        cardTTSUtil.release();

        // Always stop service on unbind so the service will not be reused
        // for the next binding.
        return false;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
    }

    public void startPlaying(Card startCard) {
        Preconditions.checkNotNull(startCard);
        // Always to create a new context if we start playing to ensure it is playing
        // from a clean state.
        reset();

        cardPlayerContext.setCurrentCard(startCard);
        cardPlayerContext.getState().transition(cardPlayerContext, CardPlayerMessage.START_PLAYING);
        showNotification();
    }

    public void skipToNext() {
        cardPlayerContext.getState().transition(cardPlayerContext, CardPlayerMessage.GO_TO_NEXT);
    }

    public void skipToPrev() {
        cardPlayerContext.getState().transition(cardPlayerContext, CardPlayerMessage.GO_TO_PREV);
    }

    public void stopPlaying() {
        Log.v(TAG, "Stop playing");
        cancelNotification();
        cardPlayerContext.getState().transition(cardPlayerContext, CardPlayerMessage.STOP_PLAYING);
    }

    /*
     * Stop playing and reset the context
     */
    public void reset() {
        // When we reset, we want to know the current card
        // to set so the newly created context will have the
        // current card previously set
        // If it is null, we will leave it undertermined.
        Card currentCard = null;

        if (cardPlayerContext != null) {
            stopPlaying();
            currentCard = cardPlayerContext.getCurrentCard();
        }

        cardPlayerContext = new CardPlayerContext(
                cardPlayerEventHandler,
                cardTTSUtil,
                handler,
                dbOpenHelper,
                option.getCardPlayerIntervalBetweenQA(),
                option.getCardPlayerIntervalBetweenCards(),
                option.getCardPlayerShuffleEnabled(),
                option.getCardPlayerRepeatEnabled());

        if (currentCard != null) {
            cardPlayerContext.setCurrentCard(currentCard);
        }
    }

    /*
     * A notification is shown if the player is playing.
     * This also put the service in foreground mode to prevent the service
     * being terminated.
     */
    private void showNotification() {
        Intent resultIntent = new Intent(this, CardPlayerActivity.class);

        // Make sure to resume the activity instead of creating a new one.
        resultIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);

        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, resultIntent, 0);
        NotificationCompat.Builder mBuilder =
            new NotificationCompat.Builder(this)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(getString(R.string.card_player_notification_title))
            .setContentText(getString(R.string.card_player_notification_text))
            .setContentIntent(pendingIntent)
            .setOngoing(true);

        // Basically make the service foreground so a notification is shown
        // And the service is less susceptible to be kill by Android system.
        startForeground(NOTIFICATION_ID, mBuilder.build());
    }

    /*
     * This handler is used for callback from the CardPlayerService's startPlaying.
     * This implementation send broadcast for ACTION_GO_TO_CARD and the 
     * CardPlayerFragment will register the broadcast receiver in CardPlaeyrActivity.
     */
    private CardPlayerEventHandler cardPlayerEventHandler = new CardPlayerEventHandler() {
        @Override
        public void onPlayCard(Card card) {
            Intent intent = new Intent();
            intent.setAction(ACTION_GO_TO_CARD);
            intent.putExtra(EXTRA_CURRENT_CARD_ID, card.getId());
            sendBroadcast(intent);
        }

        @Override
        public void onStopPlaying() {
            Intent intent = new Intent();
            intent.setAction(ACTION_PLAYING_STOPPED);
            sendBroadcast(intent);
        }
    };

    private void cancelNotification() {
        stopForeground(true);
    }

    // A local binder that works for local methos call.
    public class LocalBinder extends Binder {
        public CardPlayerService getService() {
            return CardPlayerService.this;
        }

        public Card getCurrentPlayingCard() {
            if (cardPlayerContext != null) {
                return cardPlayerContext.getCurrentCard();
            } else {
                return null;
            }
        }
    }

}

