package com.psyagent.bootstrap.tool;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.psyagent.store.entity.Appointment;
import com.psyagent.store.mapper.AppointmentMapper;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 预约登记。
 * <p>
 * 幂等是这里的重点：模型客户端会因为超时重试、用户也会连点两次提交，
 * 没有 idempotencyKey 就会生成两条待确认预约，后台要多打两通电话。
 * 用唯一索引兜底（并发下两个请求同时插入，第二个会撞唯一键），
 * 撞键后再查一次返回已有记录，而不是直接报失败。
 */
@Component
public class AppointmentTool implements AgentTool {

    private final AppointmentMapper mapper;

    public AppointmentTool(AppointmentMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public String name() {
        return "create_appointment";
    }

    @Override
    public String description() {
        return "登记一次心理咨询预约。需要联系方式与期望时间，返回预约编号与状态。"
                + "重复提交同一 idempotencyKey 会返回首次创建的结果。";
    }

    @Override
    public Map<String, Object> inputSchema() {
        Map<String, Object> root = new LinkedHashMap<>();
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("contact", Map.of("type", "string", "description", "手机号或邮箱"));
        props.put("preferredTime", Map.of("type", "string", "description", "期望时间，如 周六下午"));
        props.put("topic", Map.of("type", "string", "description", "想聊的主题，可选"));
        props.put("idempotencyKey", Map.of("type", "string", "description", "幂等键，可选但强烈建议提供"));
        root.put("type", "object");
        root.put("properties", props);
        root.put("required", List.of("contact", "preferredTime"));
        return root;
    }

    @Override
    public ToolResult execute(Map<String, Object> args, ToolContext context) {
        String contact = ToolArgs.str(args, "contact", "");
        String preferredTime = ToolArgs.str(args, "preferredTime", "");
        if (contact.isBlank() || preferredTime.isBlank()) {
            return ToolResult.fail("contact 与 preferredTime 均为必填");
        }
        if (contact.length() > 128 || preferredTime.length() > 64) {
            return ToolResult.fail("联系方式或期望时间过长");
        }
        String key = ToolArgs.str(args, "idempotencyKey", "");

        try {
            Appointment existing = findByKey(key);
            if (existing != null) {
                return ToolResult.ok("该预约已存在，未重复创建：" + existing.getId(),
                        toData(existing, true));
            }

            Appointment appointment = new Appointment();
            appointment.setUserId(context.userId() == null ? 0L : context.userId());
            appointment.setConversationId(context.conversationId());
            appointment.setContact(contact);
            appointment.setPreferredTime(preferredTime);
            appointment.setTopic(truncate(ToolArgs.str(args, "topic", ""), 255));
            appointment.setStatus("PENDING");
            appointment.setIdempotencyKey(key.isBlank() ? null : key);
            appointment.setTraceId(context.traceId());

            try {
                mapper.insert(appointment);
            } catch (RuntimeException e) {
                // 并发下撞唯一键：返回先插入的那条，语义上仍是成功
                Appointment concurrent = findByKey(key);
                if (concurrent != null) {
                    return ToolResult.ok("并发重复提交，已返回既有预约：" + concurrent.getId(),
                            toData(concurrent, true));
                }
                return ToolResult.fail("预约写入失败：" + e.getMessage());
            }
            return ToolResult.ok("预约已登记，编号 " + appointment.getId() + "，状态待确认，"
                    + "工作人员会按你留的联系方式与你确认", toData(appointment, false));
        } catch (RuntimeException e) {
            return ToolResult.fail("预约服务异常：" + e.getMessage());
        }
    }

    private Appointment findByKey(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        return mapper.selectOne(new LambdaQueryWrapper<Appointment>()
                .eq(Appointment::getIdempotencyKey, key)
                .last("LIMIT 1"));
    }

    /** 返回给调用方的结果不含完整联系方式，避免 PII 出现在 MCP 响应和模型上下文里。 */
    private static Map<String, Object> toData(Appointment appointment, boolean duplicate) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("appointmentId", appointment.getId());
        data.put("status", appointment.getStatus());
        data.put("preferredTime", appointment.getPreferredTime());
        data.put("contactMasked", mask(appointment.getContact()));
        data.put("duplicate", duplicate);
        return data;
    }

    private static String mask(String text) {
        if (text == null || text.length() <= 4) {
            return "****";
        }
        return "****" + text.substring(text.length() - 4);
    }

    private static String truncate(String text, int max) {
        return text == null || text.length() <= max ? text : text.substring(0, max);
    }
}
