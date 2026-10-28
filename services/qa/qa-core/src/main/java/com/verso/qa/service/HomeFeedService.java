package com.verso.qa.service;

import com.verso.document.api.dto.Reader;
import com.verso.qa.api.dto.HomeFeedResponse;

/** The home screen's "Ekibinizin son çıktıları" (ADR-0058): what the caller may read, nothing about who made it. */
public interface HomeFeedService {

    /** At most this many cards and this many procedures. */
    int PER_KIND = 5;

    HomeFeedResponse feed(Reader reader);
}
