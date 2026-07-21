package com.bejo.push;

import com.bejo.constant.AdEvent;
import com.bejo.constant.PushType;
import com.bejo.entity.AdCampaign;
import com.bejo.entity.AdDelivery;

import java.util.Map;

/**
 * A voice-ad step to the one target device: {@link AdEvent#PREPARE} (download the audio, with a
 * signed URL + integrity facts) or {@link AdEvent#PLAY} (play it). No title/body — the app drives
 * the whole interaction. Urgent, so the app acts promptly.
 */
public final class AdsPushPayload extends AbstractPushPayload {

    private final String adEvent;
    private final AdCampaign campaign;
    private final AdDelivery delivery;
    private final String audioUrl; // PREPARE only

    private AdsPushPayload(String adEvent, AdCampaign campaign, AdDelivery delivery, String audioUrl) {
        super(PushType.ADS, null, null);
        this.adEvent = adEvent;
        this.campaign = campaign;
        this.delivery = delivery;
        this.audioUrl = audioUrl;
    }

    /** Tell the device to download the audio from {@code audioUrl} and verify it. */
    public static AdsPushPayload prepare(AdCampaign campaign, AdDelivery delivery, String audioUrl) {
        return new AdsPushPayload(AdEvent.PREPARE, campaign, delivery, audioUrl);
    }

    /** Tell the device to play the already-downloaded audio. */
    public static AdsPushPayload play(AdCampaign campaign, AdDelivery delivery) {
        return new AdsPushPayload(AdEvent.PLAY, campaign, delivery, null);
    }

    @Override
    public boolean urgent() {
        return true;
    }

    @Override
    protected void putExtra(Map<String, String> data) {
        data.put("adEvent", adEvent);
        data.put("campaignId", String.valueOf(campaign.getId()));
        data.put("deliveryId", String.valueOf(delivery.getId()));
        data.put("campaignName", campaign.getName());
        if (AdEvent.PREPARE.equals(adEvent)) {
            data.put("audioUrl", audioUrl);
            data.put("sha256", campaign.getAudioSha256());
            data.put("sizeBytes", String.valueOf(campaign.getAudioSizeBytes()));
            data.put("contentType", campaign.getAudioContentType());
        }
    }
}
