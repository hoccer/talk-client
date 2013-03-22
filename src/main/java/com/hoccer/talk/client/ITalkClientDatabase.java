package com.hoccer.talk.client;

import com.hoccer.talk.model.TalkClient;
import com.hoccer.talk.model.TalkDelivery;
import com.hoccer.talk.model.TalkMessage;

public interface ITalkClientDatabase {

    public TalkClient getClient();

    public TalkMessage getMessageByTag(String messageTag) throws Exception;
    public TalkDelivery[] getDeliveriesByTag(String messageTag) throws Exception;

}
