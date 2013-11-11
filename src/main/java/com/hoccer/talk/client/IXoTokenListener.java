package com.hoccer.talk.client;

import com.hoccer.talk.client.model.TalkClientSmsToken;

import java.util.List;

public interface IXoTokenListener {

    public void onTokensChanged(List<TalkClientSmsToken> tokens, boolean newTokens);

}
