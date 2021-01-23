/*
 * Copyright (C) 2012-2020 Markus Junginger, greenrobot (http://greenrobot.org)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.greenrobot.eventbus;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.logging.Level;

/**
 * EventBus is a central publish/subscribe event system for Java and Android.
 * Events are posted ({@link #post(Object)}) to the bus, which delivers it to subscribers that have a matching handler
 * method for the event type.
 * To receive events, subscribers must register themselves to the bus using {@link #register(Object)}.
 * Once registered, subscribers receive events until {@link #unregister(Object)} is called.
 * Event handling methods must be annotated by {@link Subscribe}, must be public, return nothing (void),
 * and have exactly one parameter (the event).
 *
 * @author Markus Junginger, greenrobot
 */
public class EventBus {

    /** Log tag, apps may override it. */
    public static String TAG = "EventBus";


    //----------------------
    // 订阅--start
    //----------------------

    /** 订阅者订阅的事件对应类型及其父类和实现的接口的缓存，
     * key:eventType，value:Object的ArrayList，Object对象为eventType的父类或接口. */
    private static final Map<Class<?>, List<Class<?>>> eventTypesCache = new HashMap<>();
    /** 订阅者的保存队列。
     * key:eventType，value:Subscription的ArrayList，
     * 其中Subscription为订阅者信息，由 subscriber, subscriberMethod, priority 构成. */
    private final Map<Class<?>, CopyOnWriteArrayList<Subscription>> subscriptionsByEventType;
    /** 订阅者订阅的事件的保存队列。
     * key:subscriber，value:eventType的ArrayList. */
    private final Map<Object, List<Class<?>>> typesBySubscriber;
    /** 订阅的Sticky事件的保存队列。
     * key:eventType，value:event。
     * 由此可以看出对于同一个 eventType 最多只会有一个 event 存在. */
    private final Map<Class<?>, Object> stickyEvents;
    /** 订阅者响应函数信息存储和查找类. */
    private final SubscriberMethodFinder subscriberMethodFinder;
    /** 订阅者信息索引总数. */
    private final int indexCount;

    //----------------------
    // 订阅--end
    //----------------------


    //----------------------
    // 发布--start
    //----------------------

    // @Nullable
    private final MainThreadSupport mainThreadSupport;
    // @Nullable
    private final Poster mainThreadPoster;
    private final BackgroundPoster backgroundPoster;
    private final AsyncPoster asyncPoster;
    /**
     * 异步和 BackGround 处理方式的线程池.
     */
    private final ExecutorService executorService;

    //----------------------
    // 发布--end
    //----------------------


    /**
     * For ThreadLocal, much faster to set (and get multiple values).
     * 线程本地变量对象
     */
    final static class PostingThreadState {
        /** 事件队列 */
        final List<Object> eventQueue = new ArrayList<>();
        /** 是否正在分发中 */
        boolean isPosting;
        /** 是否在主线程 */
        boolean isMainThread;
        /** 订阅者信息 */
        Subscription subscription;
        /** 事件实例 */
        Object event;
        /** 是否取消 */
        boolean canceled;
    }
    /** 当前线程的 post 信息，包括事件队列、是否正在分发中、是否在主线程、订阅者信息、事件实例、是否取消. */
    private final ThreadLocal<PostingThreadState> currentPostingThreadState = new ThreadLocal<PostingThreadState>() {
        @Override
        protected PostingThreadState initialValue() {
            return new PostingThreadState();
        }
    };

    //----------------------
    // 配置--start
    //----------------------

    /** 当调用事件处理函数异常时是否抛出异常，默认为 false. */
    private final boolean throwSubscriberException;
    /** 当调用事件处理函数异常时是否打印异常信息，默认为 true. */
    private final boolean logSubscriberExceptions;
    /** 当没有订阅者订阅该事件时是否打印日志，默认为 true. */
    private final boolean logNoSubscriberMessages;
    /** 当调用事件处理函数异常时是否发送 SubscriberExceptionEvent 事件，
     * 若此开关打开，订阅者可通过public void onEvent(SubscriberExceptionEvent event) 订阅该事件进行处理，默认为 true . */
    private final boolean sendSubscriberExceptionEvent;
    /** 当没有事件处理函数对事件处理时是否发送 NoSubscriberEvent 事件，
     * 若此开关打开，订阅者可通过public void onEvent(NoSubscriberEvent event) 订阅该事件进行处理，默认为 true. */
    private final boolean sendNoSubscriberEvent;

    /** 是否支持事件继承，默认为 true. */
    private final boolean eventInheritance;

    private final Logger logger;

    //----------------------
    // 配置--end
    //----------------------

    //----------------------
    // 构建--start
    //----------------------

    /** 默认的 EventBus 实例，根据EventBus.getDefault()函数得到. */
    static volatile EventBus defaultInstance;
    /** Convenience singleton for apps using a process-wide EventBus instance. */
    public static EventBus getDefault() {
        EventBus instance = defaultInstance;
        if (instance == null) {
            synchronized (EventBus.class) {
                instance = EventBus.defaultInstance;
                if (instance == null) {
                    instance = EventBus.defaultInstance = new EventBus();
                }
            }
        }
        return instance;
    }

    /** 默认的 EventBus Builder. */
    private static final EventBusBuilder DEFAULT_BUILDER = new EventBusBuilder();

    public static EventBusBuilder builder() {
        return new EventBusBuilder();
    }

    /**
     * Creates a new EventBus instance; each instance is a separate scope in which events are delivered. To use a
     * central bus, consider {@link #getDefault()}.
     */
    public EventBus() {
        this(DEFAULT_BUILDER);
    }

    EventBus(EventBusBuilder builder) {
        logger = builder.getLogger();

        subscriptionsByEventType = new HashMap<>();
        typesBySubscriber = new HashMap<>();
        stickyEvents = new ConcurrentHashMap<>();

        mainThreadSupport = builder.getMainThreadSupport();
        mainThreadPoster = mainThreadSupport != null ? mainThreadSupport.createPoster(this) : null;
        backgroundPoster = new BackgroundPoster(this);
        asyncPoster = new AsyncPoster(this);

        indexCount = builder.subscriberInfoIndexes != null ? builder.subscriberInfoIndexes.size() : 0;
        subscriberMethodFinder = new SubscriberMethodFinder(builder.subscriberInfoIndexes,
                builder.strictMethodVerification, builder.ignoreGeneratedIndex);

        logSubscriberExceptions = builder.logSubscriberExceptions;
        logNoSubscriberMessages = builder.logNoSubscriberMessages;
        sendSubscriberExceptionEvent = builder.sendSubscriberExceptionEvent;
        sendNoSubscriberEvent = builder.sendNoSubscriberEvent;
        throwSubscriberException = builder.throwSubscriberException;
        eventInheritance = builder.eventInheritance;
        executorService = builder.executorService;
    }

    //----------------------
    // 构建--end
    //----------------------

    //----------------------
    // 注册--start
    //----------------------

    /**
     * Registers the given subscriber to receive events. Subscribers must call {@link #unregister(Object)} once they
     * are no longer interested in receiving events.
     * <p/>
     * Subscribers have event handling methods that must be annotated by {@link Subscribe}.
     * The {@link Subscribe} annotation also allows configuration like {@link
     * ThreadMode} and priority.
     *
     * 注册.
     */
    public void register(Object subscriber) {
        Class<?> subscriberClass = subscriber.getClass();
        //订阅者的响应函数信息
        List<SubscriberMethod> subscriberMethods = subscriberMethodFinder.findSubscriberMethods(subscriberClass);
        synchronized (this) {
            //将注册者变为订阅者
            for (SubscriberMethod subscriberMethod : subscriberMethods) {
                subscribe(subscriber, subscriberMethod);
            }
        }
    }

    //----------------------
    // 注册--end
    //----------------------

    //----------------------
    // 注销--start
    //----------------------

    /**
     * Unregisters the given subscriber from all event classes.
     * 注销
     */
    public synchronized void unregister(Object subscriber) {
        List<Class<?>> subscribedTypes = typesBySubscriber.get(subscriber);
        if (subscribedTypes != null) {
            for (Class<?> eventType : subscribedTypes) {
                unsubscribeByEventType(subscriber, eventType);
            }
            typesBySubscriber.remove(subscriber);
        } else {
            logger.log(Level.WARNING, "Subscriber to unregister was not registered before: " + subscriber.getClass());
        }
    }

    //----------------------
    // 注销--end
    //----------------------

    //----------------------
    // 订阅--start
    //----------------------

    /**
     * Must be called in synchronized block
     * 订阅
     *
     * @param subscriber 订阅者
     * @param subscriberMethod 订阅者事件响应方法
     */
    private void subscribe(Object subscriber, SubscriberMethod subscriberMethod) {
        Class<?> eventType = subscriberMethod.eventType;
        Subscription newSubscription = new Subscription(subscriber, subscriberMethod);
        CopyOnWriteArrayList<Subscription> subscriptions = subscriptionsByEventType.get(eventType);
        if (subscriptions == null) {
            /*
             *添加事件类型的订阅者到列表
             */
            subscriptions = new CopyOnWriteArrayList<>();
            subscriptionsByEventType.put(eventType, subscriptions);
        } else {
            /*
             *如果已有订阅者信息集合包含了新的订阅者信息，则抛出异常
             */
            if (subscriptions.contains(newSubscription)) {
                throw new EventBusException("Subscriber " + subscriber.getClass() + " already registered to event "
                        + eventType);
            }
        }

        /*
         *按优先级规划新的订阅者在列表中的排序
         */
        int size = subscriptions.size();
        for (int i = 0; i <= size; i++) {
            if (i == size || subscriberMethod.priority > subscriptions.get(i).subscriberMethod.priority) {
                subscriptions.add(i, newSubscription);
                break;
            }
        }

        /*
         *添加订阅者注册的事件类型到列表
         */
        List<Class<?>> subscribedEvents = typesBySubscriber.get(subscriber);
        if (subscribedEvents == null) {
            subscribedEvents = new ArrayList<>();
            typesBySubscriber.put(subscriber, subscribedEvents);
        }
        subscribedEvents.add(eventType);

        /*
         * 黏性事件处理
         * 发布黏性事件给订阅者
         */
        if (subscriberMethod.sticky) {
            if (eventInheritance) {
                // Existing sticky events of all subclasses of eventType have to be considered.
                // Note: Iterating over all events may be inefficient with lots of sticky events,
                // thus data structure should be changed to allow a more efficient lookup
                // (e.g. an additional map storing sub classes of super classes: Class -> List<Class>).
                Set<Map.Entry<Class<?>, Object>> entries = stickyEvents.entrySet();
                for (Map.Entry<Class<?>, Object> entry : entries) {
                    Class<?> candidateEventType = entry.getKey();
                    if (eventType.isAssignableFrom(candidateEventType)) {
                        Object stickyEvent = entry.getValue();
                        checkPostStickyEventToSubscription(newSubscription, stickyEvent);
                    }
                }
            } else {
                Object stickyEvent = stickyEvents.get(eventType);
                checkPostStickyEventToSubscription(newSubscription, stickyEvent);
            }
        }
    }

    /**
     * 检查是否发布黏性事件给订阅者
     *
     * @param newSubscription 订阅者
     * @param stickyEvent 黏性事件
     */
    private void checkPostStickyEventToSubscription(Subscription newSubscription, Object stickyEvent) {
        if (stickyEvent != null) {
            // If the subscriber is trying to abort the event, it will fail (event is not tracked in posting state)
            // --> Strange corner case, which we don't take care of here.
            postToSubscription(newSubscription, stickyEvent, isMainThread());
        }
    }

    /**
     * Checks if the current thread is running in the main thread.
     * If there is no main thread support (e.g. non-Android), "true" is always returned. In that case MAIN thread
     * subscribers are always called in posting thread, and BACKGROUND subscribers are always called from a background
     * poster.
     */
    private boolean isMainThread() {
        return mainThreadSupport == null || mainThreadSupport.isMainThread();
    }

    /**
     * 是否已经注册了订阅者
     *
     * @param subscriber 订阅者
     */
    public synchronized boolean isRegistered(Object subscriber) {
        return typesBySubscriber.containsKey(subscriber);
    }

    /**
     * Only updates subscriptionsByEventType, not typesBySubscriber! Caller must update typesBySubscriber.
     * 取消订阅
     */
    private void unsubscribeByEventType(Object subscriber, Class<?> eventType) {
        List<Subscription> subscriptions = subscriptionsByEventType.get(eventType);
        if (subscriptions != null) {
            int size = subscriptions.size();
            for (int i = 0; i < size; i++) {
                Subscription subscription = subscriptions.get(i);
                if (subscription.subscriber == subscriber) {
                    subscription.active = false;
                    subscriptions.remove(i);
                    i--;
                    size--;
                }
            }
        }
    }

    //----------------------
    // 订阅--end
    //----------------------

    //----------------------
    // 发布--start
    //----------------------

    /**
     * Posts the given event to the event bus.
     * 发布事件
     */
    public void post(Object event) {
        PostingThreadState postingState = currentPostingThreadState.get();//线程本地变量对象
        List<Object> eventQueue = postingState.eventQueue;
        eventQueue.add(event);//将事件添加进事件队列

        if (!postingState.isPosting) {//非发布事件中
            postingState.isMainThread = isMainThread();
            postingState.isPosting = true;
            if (postingState.canceled) {//发布事件被取消
                throw new EventBusException("Internal error. Abort state was not reset");
            }
            try {
                while (!eventQueue.isEmpty()) {//事件队列非空
                    postSingleEvent(eventQueue.remove(0), postingState);
                }
            } finally {
                /* 重置发布状态 */
                postingState.isPosting = false;
                postingState.isMainThread = false;
            }
        }
    }

    /**
     * 发布单个事件
     *
     * @param event 事件
     * @param postingState 线程本地变量对象
     * @throws Error
     */
    private void postSingleEvent(Object event, PostingThreadState postingState) throws Error {
        Class<?> eventClass = event.getClass();
        boolean subscriptionFound = false;//订阅者未被发现
        if (eventInheritance) {//支持事件继承
            List<Class<?>> eventTypes = lookupAllEventTypes(eventClass);//获取类对应的本类与父类所组成的类型列表
            int countTypes = eventTypes.size();
            for (int h = 0; h < countTypes; h++) {
                Class<?> clazz = eventTypes.get(h);
                subscriptionFound |= postSingleEventForEventType(event, postingState, clazz);//只要有一次发布成功就标记--订阅者已被发现
            }
        } else {//不支持事件继承
            subscriptionFound = postSingleEventForEventType(event, postingState, eventClass);
        }
        if (!subscriptionFound) {//订阅者未被发现
            if (logNoSubscriberMessages) {
                logger.log(Level.FINE, "No subscribers registered for event " + eventClass);
            }
            //当没有事件处理函数对事件处理时是否发送 NoSubscriberEvent 事件
            if (sendNoSubscriberEvent && eventClass != NoSubscriberEvent.class &&
                    eventClass != SubscriberExceptionEvent.class) {
                post(new NoSubscriberEvent(this, event));
            }
        }
    }

    /**
     * 发布单个事件给相应事件类型
     *
     * @param event 事件
     * @param postingState 线程本地变量对象
     * @param eventClass 事件类型
     */
    private boolean postSingleEventForEventType(Object event, PostingThreadState postingState, Class<?> eventClass) {
        /* 根据事件类型获取订阅者信息 */
        CopyOnWriteArrayList<Subscription> subscriptions;
        synchronized (this) {
            subscriptions = subscriptionsByEventType.get(eventClass);
        }
        if (subscriptions != null && !subscriptions.isEmpty()) {
            for (Subscription subscription : subscriptions) {
                postingState.event = event;//线程本地变量对象中的事件
                postingState.subscription = subscription;//线程本地变量对象中的订阅者信息
                boolean aborted;//取消标记
                try {
                    postToSubscription(subscription, event, postingState.isMainThread);
                    aborted = postingState.canceled;
                } finally {
                    /* 重置发布状态 */
                    postingState.event = null;
                    postingState.subscription = null;
                    postingState.canceled = false;
                }
                if (aborted) {
                    break;
                }
            }
            return true;
        }
        return false;
    }

    /**
     * 发布到订阅者
     *
     * @param subscription 订阅者信息
     * @param event 事件
     * @param isMainThread 发布者是否主线程
     */
    private void postToSubscription(Subscription subscription, Object event, boolean isMainThread) {
        switch (subscription.subscriberMethod.threadMode) {//订阅者线程模式
            case POSTING://订阅者在当前线程
                invokeSubscriber(subscription, event);
                break;
            case MAIN://订阅者在主线程
                if (isMainThread) {//发布者在主线程，订阅者在主线程
                    invokeSubscriber(subscription, event);
                } else {//发布者在子线程，订阅者在主线程
                    mainThreadPoster.enqueue(subscription, event);
                }
                break;
            case MAIN_ORDERED:
                if (mainThreadPoster != null) {
                    mainThreadPoster.enqueue(subscription, event);
                } else {
                    // temporary: technically not correct as poster not decoupled from subscriber
                    invokeSubscriber(subscription, event);
                }
                break;
            case BACKGROUND://订阅者在后台线程
                if (isMainThread) {//发布者在主线程，订阅者在子线程
                    backgroundPoster.enqueue(subscription, event);
                } else {//发布者在子线程，订阅者在子线程
                    invokeSubscriber(subscription, event);
                }
                break;
            case ASYNC://订阅者在异步线程
                asyncPoster.enqueue(subscription, event);
                break;
            default:
                throw new IllegalStateException("Unknown thread mode: " + subscription.subscriberMethod.threadMode);
        }
    }

    /**
     * Invokes the subscriber if the subscriptions is still active. Skipping subscriptions prevents race conditions
     * between {@link #unregister(Object)} and event delivery. Otherwise the event might be delivered after the
     * subscriber unregistered. This is particularly important for main thread delivery and registrations bound to the
     * live cycle of an Activity or Fragment.
     * 调用发布者方法
     *
     * @param pendingPost 待发布信息
     */
    void invokeSubscriber(PendingPost pendingPost) {
        Object event = pendingPost.event;
        Subscription subscription = pendingPost.subscription;
        PendingPost.releasePendingPost(pendingPost);
        if (subscription.active) {
            invokeSubscriber(subscription, event);
        }
    }

    /**
     * 调用发布者方法
     *
     * @param subscription 订阅者信息
     * @param event 事件
     */
    void invokeSubscriber(Subscription subscription, Object event) {
        try {
            subscription.subscriberMethod.method.invoke(subscription.subscriber, event);
        } catch (InvocationTargetException e) {
            handleSubscriberException(subscription, event, e.getCause());
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Unexpected exception", e);
        }
    }

    /**
     * 处理订阅者异常
     *
     * @param subscription 订阅者信息
     * @param event 事件
     * @param cause 异常
     */
    private void handleSubscriberException(Subscription subscription, Object event, Throwable cause) {
        if (event instanceof SubscriberExceptionEvent) {
            if (logSubscriberExceptions) {
                // Don't send another SubscriberExceptionEvent to avoid infinite event recursion, just log
                logger.log(Level.SEVERE, "SubscriberExceptionEvent subscriber " + subscription.subscriber.getClass()
                        + " threw an exception", cause);
                SubscriberExceptionEvent exEvent = (SubscriberExceptionEvent) event;
                logger.log(Level.SEVERE, "Initial event " + exEvent.causingEvent + " caused exception in "
                        + exEvent.causingSubscriber, exEvent.throwable);
            }
        } else {
            if (throwSubscriberException) {
                throw new EventBusException("Invoking subscriber failed", cause);
            }
            if (logSubscriberExceptions) {
                logger.log(Level.SEVERE, "Could not dispatch event: " + event.getClass() + " to subscribing class "
                        + subscription.subscriber.getClass(), cause);
            }
            if (sendSubscriberExceptionEvent) {
                SubscriberExceptionEvent exEvent = new SubscriberExceptionEvent(this, cause, event,
                        subscription.subscriber);
                post(exEvent);
            }
        }
    }

    //----------------------
    // 发布--end
    //----------------------


    //----------------------
    // 黏性事件--start
    //----------------------

    /**
     * Posts the given event to the event bus and holds on to the event (because it is sticky). The most recent sticky
     * event of an event's type is kept in memory for future access by subscribers using {@link Subscribe#sticky()}.
     * 发布黏性事件。
     * 黏性事件：事件发布后依然存在实例对象的事件，需要在列表中手动删除。
     */
    public void postSticky(Object event) {
        /*
         *黏性事件入列
         */
        synchronized (stickyEvents) {
            stickyEvents.put(event.getClass(), event);
        }
        // Should be posted after it is putted, in case the subscriber wants to remove immediately
        post(event);
    }

    /**
     * Gets the most recent sticky event for the given type.
     * 获取黏性事件。将黏性事件转成T
     *
     * @see #postSticky(Object)
     */
    public <T> T getStickyEvent(Class<T> eventType) {
        synchronized (stickyEvents) {
            return eventType.cast(stickyEvents.get(eventType));
        }
    }

    /**
     * Remove and gets the recent sticky event for the given event type.
     * 删除黏性事件。
     *
     * @see #postSticky(Object)
     */
    public <T> T removeStickyEvent(Class<T> eventType) {
        synchronized (stickyEvents) {
            return eventType.cast(stickyEvents.remove(eventType));
        }
    }

    /**
     * Removes the sticky event if it equals to the given event.
     * 删除黏性事件。
     *
     * @return true if the events matched and the sticky event was removed.
     */
    public boolean removeStickyEvent(Object event) {
        synchronized (stickyEvents) {
            Class<?> eventType = event.getClass();
            Object existingEvent = stickyEvents.get(eventType);
            if (event.equals(existingEvent)) {
                stickyEvents.remove(eventType);
                return true;
            } else {
                return false;
            }
        }
    }

    /**
     * Removes all sticky events.
     * 清空黏性事件。
     */
    public void removeAllStickyEvents() {
        synchronized (stickyEvents) {
            stickyEvents.clear();
        }
    }

    //----------------------
    // 黏性事件--end
    //----------------------


    //----------------------
    // 获取类对应的本类与父类所组成的类别列表--start
    //----------------------

    /**
     * Looks up all Class objects including super classes and interfaces. Should also work for interfaces.
     * 获取类对应的本类与父类所组成的类别列表
     */
    private static List<Class<?>> lookupAllEventTypes(Class<?> eventClass) {
        synchronized (eventTypesCache) {
            List<Class<?>> eventTypes = eventTypesCache.get(eventClass);
            if (eventTypes == null) {
                eventTypes = new ArrayList<>();
                Class<?> clazz = eventClass;
                while (clazz != null) {
                    eventTypes.add(clazz);
                    addInterfaces(eventTypes, clazz.getInterfaces());
                    clazz = clazz.getSuperclass();
                }
                eventTypesCache.put(eventClass, eventTypes);
            }
            return eventTypes;
        }
    }

    /**
     * Recurses through super interfaces.
     * 递归添加父接口
     */
    static void addInterfaces(List<Class<?>> eventTypes, Class<?>[] interfaces) {
        for (Class<?> interfaceClass : interfaces) {
            if (!eventTypes.contains(interfaceClass)) {
                eventTypes.add(interfaceClass);
                addInterfaces(eventTypes, interfaceClass.getInterfaces());
            }
        }
    }

    //----------------------
    // 获取类对应的本类与父类所组成的类别列表--end
    //----------------------

    //----------------------
    // 访问方法--start
    //----------------------

    ExecutorService getExecutorService() {
        return executorService;
    }

    //----------------------
    // 访问方法--end
    //----------------------

    /**
     * Called from a subscriber's event handling method, further event delivery will be canceled. Subsequent
     * subscribers
     * won't receive the event. Events are usually canceled by higher priority subscribers (see
     * {@link Subscribe#priority()}). Canceling is restricted to event handling methods running in posting thread
     * {@link ThreadMode#POSTING}.
     *
     * 取消事件分发。
     * 在订阅者的事件处理方法中被调用，较靠后的事件分发将被取消。随后的订阅者将收不到事件。
     * 取消被禁用在POSTING模式的事件处理方法中。
     */
    public void cancelEventDelivery(Object event) {
        PostingThreadState postingState = currentPostingThreadState.get();
        if (!postingState.isPosting) {
            throw new EventBusException(
                    "This method may only be called from inside event handling methods on the posting thread");
        } else if (event == null) {
            throw new EventBusException("Event may not be null");
        } else if (postingState.event != event) {
            throw new EventBusException("Only the currently handled event may be aborted");
        } else if (postingState.subscription.subscriberMethod.threadMode != ThreadMode.POSTING) {
            throw new EventBusException(" event handlers may only abort the incoming event");
        }

        postingState.canceled = true;
    }

    /**
     * 是否有事件eventClass的订阅者
     *
     * @param eventClass 事件类型
     */
    public boolean hasSubscriberForEvent(Class<?> eventClass) {
        List<Class<?>> eventTypes = lookupAllEventTypes(eventClass);
        if (eventTypes != null) {
            int countTypes = eventTypes.size();
            for (int h = 0; h < countTypes; h++) {
                Class<?> clazz = eventTypes.get(h);
                CopyOnWriteArrayList<Subscription> subscriptions;
                synchronized (this) {
                    subscriptions = subscriptionsByEventType.get(clazz);
                }
                if (subscriptions != null && !subscriptions.isEmpty()) {
                    return true;
                }
            }
        }
        return false;
    }

    /** For unit test primarily. */
    public static void clearCaches() {
        SubscriberMethodFinder.clearCaches();
        eventTypesCache.clear();
    }


    /**
     * For internal use only.
     */
    public Logger getLogger() {
        return logger;
    }

    // Just an idea: we could provide a callback to post() to be notified, an alternative would be events, of course...
    /* public */interface PostCallback {
        void onPostCompleted(List<SubscriberExceptionEvent> exceptionEvents);
    }

    @Override
    public String toString() {
        return "EventBus[indexCount=" + indexCount + ", eventInheritance=" + eventInheritance + "]";
    }
}
