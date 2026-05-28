//package com.paicli.llm;
//
//import com.fasterxml.jackson.databind.JsonNode;
//import com.fasterxml.jackson.databind.node.ArrayNode;
//import com.fasterxml.jackson.databind.node.JsonNodeFactory;
//import com.fasterxml.jackson.databind.node.ObjectNode;
//import okhttp3.*;
//
//import java.io.IOException;
//import java.util.List;
//import java.util.concurrent.TimeUnit;
//
//import static com.paicli.llm.AbstractOpenAiCompatibleClient.mapper;
//
//public class MiMoClient implements LlmClient{
//    private static final String API_URL = "https://token-plan-cn.xiaomimimo.com/v1/chat/completions";
//    private  static final String MODEL = "mimo-v2.5-pro";
//    private final String apiKey;
//
//    private  final OkHttpClient httpClient;
//
//    public MiMoClient(String apiKey){
//        this.apiKey = apiKey;
//        this.httpClient = new OkHttpClient.Builder()
//                .connectTimeout(60, TimeUnit.SECONDS)
//                .readTimeout(120, TimeUnit.SECONDS)
//                .build();
//    }
//    /**
//     * 调用大模型对话接口的核心方法
//     * @param messages  对话历史消息列表（system/user/assistant/tool）
//     * @param tools     可调用的工具列表（如函数、插件）
//     * @return          模型返回的对话响应对象
//     * @throws IOException 网络请求/JSON解析异常
//     */
//
////system：系统提示，定义 Agent 的身份和能力
////user：用户输入
////assistant：助手回复，可以包含文本或工具调用
////tool：工具执行结果
//    @Override
//    public ChatResponse chat(List<Message> messages,List<Tool> tools) throws IOException {
//        //1.构建请求体json
//
//        //ObjectMapper 创建一个空的 JSON 对象（对应 { }）
//        ObjectNode requestBody = mapper.createObjectNode();
//        //设置模型名称
//        requestBody.put("model", MODEL);
//
//        //2.组装对话历史messages
//
//        //在请求体中创建一个名为 "messages" 的JSON数组
//        ArrayNode messagesArray = requestBody.putArray("messages");
//
//        //遍历对话历史消息列表
//        for (Message msg : messages) {
//            //给数组添加一个新的JSON对象{}，表示单条对话消息
//            ObjectNode msgNode = messagesArray.addObject();
//
//            //设置消息的角色: system/user/assistant/tool
//            msgNode.put("role", msg.role());
//            //设置消息内容:文本对话内容
//            msgNode.put("content", msg.content());
//
//            //处理工具调用（assistant消息）
//            //如果当前消息是assistant消息，并且有工具调用，则添加工具调用信息
//            if(msg.toolCalls() != null && !msg.toolCalls().isEmpty()){
//                //创建一个名为 "tool_calls" 的JSON数组,存放多个工具调用
//                ArrayNode toolCallsArray = msgNode.putArray("tool_calls");
//
//                //遍历每一个工具调用
//                for (ToolCall tc : msg.toolCalls()) {
//                    ObjectNode tcNode = toolCallsArray.addObject();
//                    tcNode.put("id", tc.id());   //工具调用id
//                    tcNode.put("type", "function");
//
//
//                    //构建function节点，存放函数名和参数
//                    ObjectNode functionNode = tcNode.putObject("function");
//                    functionNode.put("name", tc.function().name());  //函数名
//                    functionNode.put("arguments", tc.function().arguments()); //函数参数
//                }
//            }
//
//            //处理工具调用结果（tool消息）
//            if(msg.toolCallId() != null){
//                msgNode.put("tool_call_id", msg.toolCallId());
//            }
//        }
//        //3.组装可调用工具列表tools
//        // 如果传入了工具定义，就添加到请求体
//        if (tools != null && !tools.isEmpty()) {
//            ArrayNode toolsArray = requestBody.putArray("tools");
//
//            for (Tool tool : tools) {
//                ObjectNode toolNode = toolsArray.addObject();
//                toolNode.put("type", "function"); // 固定类型
//
//                // 构建工具的 function 定义
//                ObjectNode functionNode = toolNode.putObject("function");
//                functionNode.put("name", tool.name());                // 工具函数名
//                functionNode.put("description", tool.description());  // 工具描述
//                functionNode.set("parameters", tool.parameters());   // 工具入参格式（JSON结构）
//            }
//        }
//
//        //4.发送http post请求
//        // 把 JSON 对象转为字符串，构建请求体，指定 Content-Type: application/json
//        RequestBody body = RequestBody.create(
//                requestBody.toString(),
//                MediaType.parse("application/json")
//        );
//
//        // 构建 OkHttp 请求
//        Request request = new Request.Builder()
//                .url(API_URL)                  // 大模型接口地址
//                .header("Authorization", "Bearer " + apiKey)  // API-KEY 鉴权
//                .post(body)                    // POST 方式提交请求体
//                .build();
//
//        // ===================== 5. 接收并解析响应 =====================
//        // 发送请求并获取响应（try-with-resources 自动关闭响应）
//        try (Response response = httpClient.newCall(request).execute()) {
//            // 获取响应原始字符串
//            String responseBody = response.body().string();
//            // 将响应字符串转为 JSON 树结构，方便读取字段
//            JsonNode root = mapper.readTree(responseBody);
//
//            // 后续解析：提取模型回复内容、工具调用、token消耗等
//            // ...
//        }
//    }
//
//}
