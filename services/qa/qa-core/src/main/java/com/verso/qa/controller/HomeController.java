package com.verso.qa.controller;

import com.verso.document.api.dto.Reader;
import com.verso.platform.security.authority.CallerGroups;
import com.verso.platform.security.web.AccountId;
import com.verso.platform.security.web.CurrentAccount;
import com.verso.qa.api.dto.HomeFeedResponse;
import com.verso.qa.service.HomeFeedService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * /v1/home (ADR-0058): the home screen's recent verified answers and approved procedures for any signed-in caller,
 * filtered with the caller's own sub and groups from the validated token (ADR-0028). Not cached: it is per caller.
 */
@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping("/v1/home")
public class HomeController {

    private final HomeFeedService feed;
    private final CallerGroups groups;

    public HomeController(HomeFeedService feed, CallerGroups groups) {
        this.feed = feed;
        this.groups = groups;
    }

    @GetMapping
    public ResponseEntity<HomeFeedResponse> home(@CurrentAccount AccountId caller) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore().cachePrivate())
                .body(feed.feed(new Reader(caller.value(), groups.current())));
    }
}
