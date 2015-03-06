package com.netflix.eureka2.client.functions;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import com.netflix.eureka2.interests.ChangeNotification;
import com.netflix.eureka2.interests.ChangeNotification.Kind;
import com.netflix.eureka2.utils.rx.RxFunctions;
import rx.Notification;
import rx.Observable;
import rx.Observable.Transformer;
import rx.functions.Func1;

/**
 * @author Tomasz Bak
 */
public final class ChangeNotificationFunctions {

    private ChangeNotificationFunctions() {
    }

    /**
     * Convert change notification stream with buffering sentinels into stream of lists, where each
     * list element contains a batch of data delineated by the markers. Only non-empty lists are
     * issued, which means that for two successive BufferSentinels from the stream, the second
     * one will be swallowed.
     *
     * an onComplete on the change notification stream is considered as another (the last) BufferSentinel.
     * an onError will not return any partial buffered data.
     *
     * @return observable of non-empty list objects
     */
    public static <T> Transformer<ChangeNotification<T>, List<ChangeNotification<T>>> buffers() {
        return new Transformer<ChangeNotification<T>, List<ChangeNotification<T>>>() {
            @Override
            public Observable<List<ChangeNotification<T>>> call(Observable<ChangeNotification<T>> notifications) {
                final AtomicReference<List<ChangeNotification<T>>> bufferRef = new AtomicReference<>();
                bufferRef.set(new ArrayList<ChangeNotification<T>>());

                return notifications
                        .materialize()
                        // concatMap as the internal observables all onComplete immediately
                        .concatMap(new Func1<Notification<ChangeNotification<T>>, Observable<List<ChangeNotification<T>>>>() {
                            @Override
                            public Observable<List<ChangeNotification<T>>> call(Notification<ChangeNotification<T>> rxNotification) {
                                List<ChangeNotification<T>> buffer = bufferRef.get();

                                switch (rxNotification.getKind()) {
                                    case OnNext:
                                        ChangeNotification<T> notification = rxNotification.getValue();
                                        if (notification.getKind() == Kind.BufferSentinel) {
                                            bufferRef.set(new ArrayList<ChangeNotification<T>>());
                                            return Observable.just(buffer);
                                        }
                                        buffer.add(notification);
                                        break;
                                    case OnCompleted:
                                        return Observable.just(buffer);  // OnCompleted == BufferSentinel
                                    case OnError:
                                        //clear the buffer and onError, don't return partial error buffer
                                        bufferRef.set(new ArrayList<ChangeNotification<T>>());
                                        return Observable.error(rxNotification.getThrowable());
                                }

                                return Observable.empty();
                            }
                        });
            }
        };
    }

    /**
     * Collapse observable of change notification batches into a set of currently known items.
     * Use a LinkedHashSet to maintain order based on insertion order.
     *
     * @return observable of distinct set objects
     */
    public static <T> Transformer<List<ChangeNotification<T>>, LinkedHashSet<T>> snapshots() {
        final LinkedHashSet<T> snapshotSet = new LinkedHashSet<>();
        return new Transformer<List<ChangeNotification<T>>, LinkedHashSet<T>>() {
            @Override
            public Observable<LinkedHashSet<T>> call(Observable<List<ChangeNotification<T>>> batches) {
                return batches.map(new Func1<List<ChangeNotification<T>>, LinkedHashSet<T>>() {
                    @Override
                    public LinkedHashSet<T> call(List<ChangeNotification<T>> batch) {
                        boolean changed = false;
                        for (ChangeNotification<T> item : batch) {
                            switch (item.getKind()) {
                                case Add:
                                case Modify:
                                    changed |= snapshotSet.add(item.getData());
                                    break;
                                case Delete:
                                    changed |= snapshotSet.remove(item.getData());
                                    break;
                                default:
                                    // no-op
                            }
                        }
                        if (changed) {
                            return new LinkedHashSet<>(snapshotSet);
                        }
                        return null;
                    }
                }).filter(RxFunctions.filterNullValuesFunc());
            }
        };
    }
}
