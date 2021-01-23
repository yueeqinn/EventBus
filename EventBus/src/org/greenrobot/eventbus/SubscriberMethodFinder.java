/*
 * Copyright (C) 2012-2016 Markus Junginger, greenrobot (http://greenrobot.org)
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

import org.greenrobot.eventbus.meta.SubscriberInfo;
import org.greenrobot.eventbus.meta.SubscriberInfoIndex;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 订阅者响应函数信息存储和查找类.
 *
 @Subscribe(threadMode = ThreadMode.PostThread) //默认方式, 在发送线程执行
 public void onUserEvent(UserEvent event) {
 }
 @Subscribe(threadMode = ThreadMode.MainThread) //在ui线程执行
 public void onUserEvent(UserEvent event) {
 }
 @Subscribe(threadMode = ThreadMode.BackgroundThread) //在后台线程执行
 public void onUserEvent(UserEvent event) {
 }
 @Subscribe(threadMode = ThreadMode.Async) //强制在后台执行
 public void onUserEvent(UserEvent event) {
 }

 由 HashMap 缓存，以 ${subscriberClassName} 为 key，SubscriberMethod 对象为元素的 ArrayList 为 value。
 findSubscriberMethods 函数用于查找订阅者响应函数，如果不在缓存中，则遍历自己的每个函数并递归父类查找，
 查找成功后保存到缓存中。遍历及查找规则为：
 a. 遍历 subscriberClass 每个方法；
 b. 该方法不以java.、javax.、android.这些 SDK 函数开头，并以 ${eventMethodName} 开头，表示可能是事件响应函数继续，否则检查下一个方法；
 c. 该方法是否是 public 的，并且不是 ABSTRACT、STATIC、BRIDGE、SYNTHETIC 修饰的，满足条件则继续。其中 BRIDGE、SYNTHETIC 为编译器生成的一些函数修饰符；
 d. 该方法是否只有 1 个参数，满足条件则继续；
 e. 该方法名为 ${eventMethodName} 则 mSubscriberThreadMode 为ThreadMode.PostThread；
 该方法名为 ${eventMethodName}MainThread 则 mSubscriberThreadMode 为ThreadMode.MainThread；
 该方法名为 ${eventMethodName}BackgroundThread 则 mSubscriberThreadMode 为ThreadMode.BackgroundThread；
 该方法名为 ${eventMethodName}Async 则 mSubscriberThreadMode 为ThreadMode.Async；
 其他情况且不在忽略名单 (skipMethodVerificationForClasses) 中则抛出异常。
 f. 得到该方法唯一的参数即事件类型 eventType，将这个方法、mSubscriberThreadMode、eventType 一起构造 SubscriberMethod 对象放到 ArrayList 中。
 g. 回到 b 遍历 subscriberClass 的下一个方法，若方法遍历结束到 h；
 h. 回到 a 遍历自己的父类，若父类遍历结束回到 i；
 i. 若 ArrayList 依然为空则抛出异常，否则会将 ArrayList 做为 value，${subscriberClassName} 做为 key 放到缓存 HashMap 中。
 对于事件函数的查找有两个小的性能优化点：
 a. 第一次查找后保存到了缓存中，即上面介绍的 HashMap
 b. 遇到 java. javax. android. 开头的类会自动停止查找 类中的 skipMethodVerificationForClasses 属性表示跳过哪些类中非法以 {eventMethodName} 开头的函数检查，若不跳过泽辉抛出异常。
 PS：在此之前的版本 EventBus 允许自定义事件响应函数名称，缓存的 HashMap key 为 ${subscriberClassName}.${eventMethodName}，这版本中此功能已经被去除。
 */
class SubscriberMethodFinder {
    /*
     * In newer class files, compilers may add methods. Those are called bridge or synthetic methods.
     * EventBus must ignore both. There modifiers are not public but defined in the Java class file format:
     * http://docs.oracle.com/javase/specs/jvms/se7/html/jvms-4.html#jvms-4.6-200-A.1
     */
    private static final int BRIDGE = 0x40;
    private static final int SYNTHETIC = 0x1000;

    private static final int MODIFIERS_IGNORE = Modifier.ABSTRACT | Modifier.STATIC | BRIDGE | SYNTHETIC;
    private static final Map<Class<?>, List<SubscriberMethod>> METHOD_CACHE = new ConcurrentHashMap<>();

    /** 订阅者信息索引对象列表. */
    private List<SubscriberInfoIndex> subscriberInfoIndexes;
    /** 是否严格的方法验证. */
    private final boolean strictMethodVerification;
    /** 是否忽略产生的索引. */
    private final boolean ignoreGeneratedIndex;

    private static final int POOL_SIZE = 4;
    private static final FindState[] FIND_STATE_POOL = new FindState[POOL_SIZE];

    SubscriberMethodFinder(List<SubscriberInfoIndex> subscriberInfoIndexes, boolean strictMethodVerification,
                           boolean ignoreGeneratedIndex) {
        this.subscriberInfoIndexes = subscriberInfoIndexes;
        this.strictMethodVerification = strictMethodVerification;
        this.ignoreGeneratedIndex = ignoreGeneratedIndex;
    }

    /**
     * 查找订阅者方法
     *
     * @param subscriberClass 订阅者类型
     */
    List<SubscriberMethod> findSubscriberMethods(Class<?> subscriberClass) {
        List<SubscriberMethod> subscriberMethods = METHOD_CACHE.get(subscriberClass);
        if (subscriberMethods != null) {
            return subscriberMethods;
        }

        if (ignoreGeneratedIndex) {
            subscriberMethods = findUsingReflection(subscriberClass);
        } else {
            subscriberMethods = findUsingInfo(subscriberClass);
        }

        if (subscriberMethods.isEmpty()) {
            throw new EventBusException("Subscriber " + subscriberClass
                    + " and its super classes have no public methods with the @Subscribe annotation");
        } else {
            METHOD_CACHE.put(subscriberClass, subscriberMethods);
            return subscriberMethods;
        }
    }

    /**
     * 以订阅者信息查找
     *
     * @param subscriberClass 订阅者信息
     */
    private List<SubscriberMethod> findUsingInfo(Class<?> subscriberClass) {
        FindState findState = prepareFindState();
        findState.initForSubscriber(subscriberClass);
        /* 递归查找 */
        while (findState.clazz != null) {
            /*以信息查找在单个类*/
            findState.subscriberInfo = getSubscriberInfo(findState);
            if (findState.subscriberInfo != null) {
                SubscriberMethod[] array = findState.subscriberInfo.getSubscriberMethods();
                for (SubscriberMethod subscriberMethod : array) {
                    if (findState.checkAdd(subscriberMethod.method, subscriberMethod.eventType)) {
                        findState.subscriberMethods.add(subscriberMethod);
                    }
                }
            } else {
                findUsingReflectionInSingleClass(findState);
            }
            findState.moveToSuperclass();
        }
        return getMethodsAndRelease(findState);
    }

    /**
     * 获取方法并释放
     *
     * @param findState
     */
    private List<SubscriberMethod> getMethodsAndRelease(FindState findState) {
        List<SubscriberMethod> subscriberMethods = new ArrayList<>(findState.subscriberMethods);
        findState.recycle();
        synchronized (FIND_STATE_POOL) {
            for (int i = 0; i < POOL_SIZE; i++) {
                if (FIND_STATE_POOL[i] == null) {
                    FIND_STATE_POOL[i] = findState;
                    break;
                }
            }
        }
        return subscriberMethods;
    }

    /**
     * 准备查找状态
     */
    private FindState prepareFindState() {
        synchronized (FIND_STATE_POOL) {
            for (int i = 0; i < POOL_SIZE; i++) {
                FindState state = FIND_STATE_POOL[i];
                if (state != null) {
                    FIND_STATE_POOL[i] = null;
                    return state;
                }
            }
        }
        return new FindState();
    }

    private SubscriberInfo getSubscriberInfo(FindState findState) {
        if (findState.subscriberInfo != null && findState.subscriberInfo.getSuperSubscriberInfo() != null) {
            SubscriberInfo superclassInfo = findState.subscriberInfo.getSuperSubscriberInfo();
            if (findState.clazz == superclassInfo.getSubscriberClass()) {
                return superclassInfo;
            }
        }
        if (subscriberInfoIndexes != null) {
            for (SubscriberInfoIndex index : subscriberInfoIndexes) {
                SubscriberInfo info = index.getSubscriberInfo(findState.clazz);
                if (info != null) {
                    return info;
                }
            }
        }
        return null;
    }

    /**
     * 以反射查找
     *
     * @param subscriberClass 订阅者类型
     */
    private List<SubscriberMethod> findUsingReflection(Class<?> subscriberClass) {
        FindState findState = prepareFindState();
        findState.initForSubscriber(subscriberClass);
        while (findState.clazz != null) {
            findUsingReflectionInSingleClass(findState);
            findState.moveToSuperclass();
        }
        return getMethodsAndRelease(findState);
    }

    /**
     * 以反射查找在单个类
     *
     * @param findState 查找状态
     */
    private void findUsingReflectionInSingleClass(FindState findState) {
        Method[] methods;
        try {
            // This is faster than getMethods, especially when subscribers are fat classes like Activities
            methods = findState.clazz.getDeclaredMethods();
        } catch (Throwable th) {
            // Workaround for java.lang.NoClassDefFoundError, see https://github.com/greenrobot/EventBus/issues/149
            try {
                methods = findState.clazz.getMethods();
            } catch (LinkageError error) { // super class of NoClassDefFoundError to be a bit more broad...
                String msg = "Could not inspect methods of " + findState.clazz.getName();
                if (ignoreGeneratedIndex) {
                    msg += ". Please consider using EventBus annotation processor to avoid reflection.";
                } else {
                    msg += ". Please make this class visible to EventBus annotation processor to avoid reflection.";
                }
                throw new EventBusException(msg, error);
            }
            findState.skipSuperClasses = true;
        }
        for (Method method : methods) {
            int modifiers = method.getModifiers();
            if ((modifiers & Modifier.PUBLIC) != 0 && (modifiers & MODIFIERS_IGNORE) == 0) {
                Class<?>[] parameterTypes = method.getParameterTypes();
                if (parameterTypes.length == 1) {
                    Subscribe subscribeAnnotation = method.getAnnotation(Subscribe.class);
                    if (subscribeAnnotation != null) {
                        Class<?> eventType = parameterTypes[0];
                        if (findState.checkAdd(method, eventType)) {
                            ThreadMode threadMode = subscribeAnnotation.threadMode();
                            findState.subscriberMethods.add(new SubscriberMethod(method, eventType, threadMode,
                                    subscribeAnnotation.priority(), subscribeAnnotation.sticky()));
                        }
                    }
                } else if (strictMethodVerification && method.isAnnotationPresent(Subscribe.class)) {
                    String methodName = method.getDeclaringClass().getName() + "." + method.getName();
                    throw new EventBusException("@Subscribe method " + methodName +
                            "must have exactly 1 parameter but has " + parameterTypes.length);
                }
            } else if (strictMethodVerification && method.isAnnotationPresent(Subscribe.class)) {
                String methodName = method.getDeclaringClass().getName() + "." + method.getName();
                throw new EventBusException(methodName +
                        " is a illegal @Subscribe method: must be public, non-static, and non-abstract");
            }
        }
    }

    static void clearCaches() {
        METHOD_CACHE.clear();
    }

    /**
     * 查找状态
     */
    static class FindState {
        /**
         * 订阅者方法列表
         */
        final List<SubscriberMethod> subscriberMethods = new ArrayList<>();
        /**
         * 事件类型与其任意方法的集合
         */
        final Map<Class, Object> anyMethodByEventType = new HashMap<>();
        /**
         * 方法键与其订阅者类型的集合
         */
        final Map<String, Class> subscriberClassByMethodKey = new HashMap<>();
        /**
         * 方法键构造者
         */
        final StringBuilder methodKeyBuilder = new StringBuilder(128);
        /**
         * 订阅者类型
         */
        Class<?> subscriberClass;
        /**
         * 临时类型
         */
        Class<?> clazz;
        /**
         * 跳过父类
         */
        boolean skipSuperClasses;
        /**
         * 订阅者信息
         */
        SubscriberInfo subscriberInfo;

        /**
         * 为订阅者初始化查找状态
         *
         * @param subscriberClass
         */
        void initForSubscriber(Class<?> subscriberClass) {
            this.subscriberClass = clazz = subscriberClass;
            skipSuperClasses = false;
            subscriberInfo = null;
        }

        void recycle() {
            subscriberMethods.clear();
            anyMethodByEventType.clear();
            subscriberClassByMethodKey.clear();
            methodKeyBuilder.setLength(0);
            subscriberClass = null;
            clazz = null;
            skipSuperClasses = false;
            subscriberInfo = null;
        }

        /**
         * 检查添加。false:在缓存中已存在，true:在缓存中不存在。
         *
         * @param method 方法
         * @param eventType 事件类型
         */
        boolean checkAdd(Method method, Class<?> eventType) {
            // 2 level check: 1st level with event type only (fast), 2nd level with complete signature when required.
            // Usually a subscriber doesn't have methods listening to the same event type.
            Object existing = anyMethodByEventType.put(eventType, method);
            if (existing == null) {
                return true;
            } else {
                if (existing instanceof Method) {
                    if (!checkAddWithMethodSignature((Method) existing, eventType)) {
                        // Paranoia check
                        throw new IllegalStateException();
                    }
                    // Put any non-Method object to "consume" the existing Method
                    anyMethodByEventType.put(eventType, this);
                }
                return checkAddWithMethodSignature(method, eventType);
            }
        }

        /**
         * 检查添加。false:在子类中已存在，true:在子类中不存在。
         *
         * @param method
         * @param eventType
         */
        private boolean checkAddWithMethodSignature(Method method, Class<?> eventType) {
            methodKeyBuilder.setLength(0);
            methodKeyBuilder.append(method.getName());
            methodKeyBuilder.append('>').append(eventType.getName());

            String methodKey = methodKeyBuilder.toString();
            Class<?> methodClass = method.getDeclaringClass();
            Class<?> methodClassOld = subscriberClassByMethodKey.put(methodKey, methodClass);
            if (methodClassOld == null || methodClassOld.isAssignableFrom(methodClass)) {
                // Only add if not already found in a sub class
                return true;
            } else {
                // Revert the put, old class is further down the class hierarchy
                subscriberClassByMethodKey.put(methodKey, methodClassOld);
                return false;
            }
        }

        /**
         * 跳转到父类
         */
        void moveToSuperclass() {
            if (skipSuperClasses) {
                clazz = null;
            } else {
                clazz = clazz.getSuperclass();
                String clazzName = clazz.getName();
                // Skip system classes, this degrades performance.
                // Also we might avoid some ClassNotFoundException (see FAQ for background).
                if (clazzName.startsWith("java.") || clazzName.startsWith("javax.") ||
                        clazzName.startsWith("android.") || clazzName.startsWith("androidx.")) {
                    clazz = null;
                }
            }
        }
    }

}
