package com.netflix.eureka2.client.resolver;

import com.netflix.eureka2.Server;
import rx.Observable;

/**
 * @author David Liu
 */
public class FallbackServerResolver extends ServerResolver {

    private final ServerResolver primary;
    private final ServerResolver fallback;

    FallbackServerResolver(ServerResolver primary, ServerResolver fallback) {
        this.primary = primary;
        this.fallback = fallback;
    }

    @Override
    public void close() {
        primary.close();
        fallback.close();
    }

    @Override
    public Observable<Server> resolve() {
        return primary.resolve()
                .onErrorResumeNext(fallback.resolve());
    }
}
