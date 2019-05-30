package com.farmer.x.communication.connection;

import com.farmer.x.communication.RemoteSession;
import com.farmer.x.communication.callback.CallBackBarrier;
import com.farmer.x.communication.callback.CallBackDataListener;
import com.farmer.x.communication.callback.CallBackLauncher;
import com.farmer.x.communication.connection.listener.CallBackReplyListener;
import com.farmer.x.communication.connection.listener.ReplyListener;
import com.farmer.x.communication.message.LoadMessage;
import com.farmer.x.communication.message.SessionMessage;
import com.farmer.x.communication.message.TransferMessage;
import com.farmer.x.communication.node.LocalNode;
import com.farmer.x.communication.node.RemoteNode;
import org.apache.commons.codec.binary.Base64;
import org.apache.commons.codec.digest.DigestUtils;

import java.util.Random;

/**
 * 统一连接对象
 * 该对象中有两个对象Receiver和Sender
 * Receiver为复用对象（每个端口监听产生的Receiver只有一个）
 * @author shaozhuguang
 * @create 2019/4/11
 * @since 1.0.0
 * @date 2019-04-18 14:49
 */
public class Connection {

    private final Random KEY_RANDOM = new Random();

    /**
     * 远端节点
     */
    private RemoteNode remoteNode;

    /**
     * 接收器
     */
    private Receiver receiver;

    /**
     * 发送器
     */
    private Sender sender;

    /**
     * 构造器
     *
     * @param receiver
     */
    public Connection(Receiver receiver) {
        this.receiver = receiver;
    }

    /**
     * 初始化RemoteSession
     *
     * @param remoteSession
     */
    public void initSession(RemoteSession remoteSession) {
        this.receiver.initRemoteSession(remoteSession.remoteSessionId(), remoteSession);
    }

    /**
     * 连接远端
     *
     * @param remoteNode
     *     远端节点
     * @param messageExecutorClass
     *     希望远端节点处理本地节点消息时的消息处理器
     * @return
     *     回调执行器
     * @throws InterruptedException
     */
    public CallBackLauncher connect(RemoteNode remoteNode, String messageExecutorClass) throws InterruptedException {
        this.remoteNode = remoteNode;
        this.sender = new Sender(this.receiver.localNode(), this.remoteNode, sessionMessage(messageExecutorClass));
        this.sender.connect();
        return this.sender.waitBooted();
    }

    /**
     * 发送请求
     *
     * 处理过程简述如下：
     * 1、生成底层消息（TransferMessage），其中消息类型为请求，用于描述本次发送的消息是用于请求应答；
     * 2、根据消息的唯一Key，生成listenKey，并生成应答监听器
     * 3、将应答监听器添加到Receiver中（Receiver中是以Map存储）
     * 4、调用Sender发送消息至对端节点
     * 5、返回应答监听器的回调数据监听对象
     *
     * @param sessionId
     *     当前SessionId
     * @param loadMessage
     *     载体消息
     * @param callBackBarrier
     *     回调栅栏
     * @return
     */
    public CallBackDataListener request(String sessionId, LoadMessage loadMessage, CallBackBarrier callBackBarrier) {

        TransferMessage transferMessage = transferMessage(sessionId, null, loadMessage, TransferMessage.MESSAGE_TYPE.TYPE_REQUEST);

        // 监听器的Key
        String listenKey = transferMessage.toListenKey();

        // 创建监听器
        CallBackReplyListener replyListener = new CallBackReplyListener(listenKey, this.remoteNode, callBackBarrier);

        // 添加监听器至Receiver
        this.receiver.addListener(replyListener);

        // 发送请求
        this.sender.send(transferMessage);

        return replyListener.callBackDataListener();
    }

    /**
     * 发送请求（自定义应答处理器）
     *
     * @param sessionId
     *     当前SessionId
     * @param replyListener
     *     自定义应答处理器
     * @param loadMessage
     *     载体消息
     * @return
     *     消息的Key（该Key会绑定唯一的replyListener）
     */
    public String request(String sessionId, ReplyListener replyListener, LoadMessage loadMessage) {

        TransferMessage transferMessage = transferMessage(sessionId, null, loadMessage, TransferMessage.MESSAGE_TYPE.TYPE_REQUEST);

        // 监听器的Key
        String listenKey = transferMessage.toListenKey();

        // 添加监听器至Receiver
        this.receiver.addListener(listenKey, replyListener);

        // 发送请求
        this.sender.send(transferMessage);

        return listenKey;
    }

    /**
     * 发送应答
     *
     * @param sessionId
     *     当前SessionID
     * @param key
     *     请求消息的Key，用于描述对应的请求
     * @param loadMessage
     *     应答的载体消息
     */
    public void reply(String sessionId, String key, LoadMessage loadMessage) {
        TransferMessage transferMessage = transferMessage(sessionId, key, loadMessage, TransferMessage.MESSAGE_TYPE.TYPE_RESPONSE);

        // 通过Sender发送数据
        this.sender.send(transferMessage);
    }

    /**
     * 移除应答监听器
     *     通常在应答处理完成后调用，防止ReceiveHandler内存耗费严重
     *
     * @param listenerKey
     *     监听器Key
     */
    public void removeReplyListener(String listenerKey) {
        this.receiver.removeListener(listenerKey);
    }

    /**
     * 生成载体消息的Key
     *
     * @param loadMessage
     * @return
     */
    private String loadKey(LoadMessage loadMessage) {
        // key每次不能一致，因此增加随机数
        byte[] randomBytes = new byte[8];
        KEY_RANDOM.nextBytes(randomBytes);
        byte[] loadBytes = loadMessage.toBytes();
        byte[] keyBytes = new byte[loadBytes.length + randomBytes.length];
        System.arraycopy(randomBytes, 0, keyBytes, 0, randomBytes.length);
        System.arraycopy(loadBytes, 0, keyBytes, randomBytes.length, loadBytes.length);
        // 使用Sha256求Hash
        byte[] sha256Bytes = DigestUtils.sha256(keyBytes);
        // 使用base64作为Key
        return Base64.encodeBase64String(sha256Bytes);
    }

    /**
     * 生成TransferMessage
     *
     * @param sessionId
     *     节点ID
     * @param key
     *     消息Key
     * @param loadMessage
     *     载体消息
     * @param messageType
     *     消息类型
     * @return
     */
    private TransferMessage transferMessage(String sessionId, String key, LoadMessage loadMessage, TransferMessage.MESSAGE_TYPE messageType) {

        if (key == null || key.length() == 0) {
            key = loadKey(loadMessage);
        }

        TransferMessage transferMessage = new TransferMessage(
                sessionId, messageType.code(), key, loadMessage.toBytes());

        return transferMessage;
    }

    /**
     * 生成SessionMessage
     *
     * @param messageExecutorClass
     *
     * @return
     */
    private SessionMessage sessionMessage(String messageExecutorClass) {

        LocalNode localNode = this.receiver.localNode();

        SessionMessage sessionMessage = new SessionMessage(
                localNode.getHostName(), localNode.getPort(), messageExecutorClass);

        return sessionMessage;
    }

    public void closeAll() {
        closeReceiver();
        closeSender();
    }

    public RemoteNode remoteNode() {
        return remoteNode;
    }

    public void closeReceiver() {
        this.receiver.close();
    }

    public void closeSender() {
        this.sender.close();
    }
}