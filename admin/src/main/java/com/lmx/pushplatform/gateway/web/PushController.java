package com.lmx.pushplatform.gateway.web;

import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.lmx.pushplatform.client.DynamicConnector;
import com.lmx.pushplatform.gateway.api.CommonResp;
import com.lmx.pushplatform.gateway.api.PushReq;
import com.lmx.pushplatform.gateway.api.StaticsReq;
import com.lmx.pushplatform.gateway.dao.AppRep;
import com.lmx.pushplatform.gateway.dao.DeviceMessageRep;
import com.lmx.pushplatform.gateway.dao.MessageRep;
import com.lmx.pushplatform.gateway.entity.*;
import com.lmx.pushplatform.gateway.util.DateUtil;
import com.lmx.pushplatform.proto.PushRequest;
import com.lmx.pushplatform.proto.PushResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.*;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/push")
@Slf4j
public class PushController {
    @Autowired
    private DynamicConnector clientDelegate;
    @Autowired
    private AppRep appRep;
    @Autowired
    private MessageRep messageRep;
    @Autowired
    private DeviceMessageRep deviceMessageRep;

    /**
     * app发布推送消息，服务端程序调用
     *
     * @param pushReq
     * @return
     */
    @PostMapping("/server")
    public CommonResp push(@RequestBody PushReq pushReq) {
        //找到app设备下的所有注册用户
        AppEntity appEntity = appRep.findByAppName(pushReq.getAppName());
        Set<UserEntity> userEntities = appEntity.getUserEntitySet();
        Set<String> sets = Sets.newHashSet();
        for (UserEntity u : userEntities) {
            sets.add(String.valueOf(u.getId()));
        }
        if (CollectionUtils.isEmpty(sets))
            return CommonResp.defaultSuccess();
        PushRequest pushRequest = new PushRequest();
        pushRequest.setMsgType(1);
        pushRequest.setMsgContent(pushReq.getMessageContent());
        pushRequest.setToId(Lists.newArrayList(sets));
        pushRequest.setPlatform(pushReq.getPlatform());
        clientDelegate.sendOnly(pushRequest);
        return CommonResp.defaultSuccess();
    }

    /**
     * 后台发布app推送消息，管理页面调用
     *
     * @param pushReq
     * @return
     */
    @PostMapping("/admin")
    public CommonResp admin(@RequestBody PushReq pushReq) {
        //找到app设备下的所有设备号
        AppEntity appEntity = appRep.findByAppName(pushReq.getAppName());
        Set<DeviceEntity> deviceEntities = appEntity.getDeviceEntitySet();
        Set<String> sets = Sets.newHashSet(deviceEntities.stream().map(DeviceEntity::getDeviceId).collect(Collectors.toSet()));
        if (CollectionUtils.isEmpty(sets)) {
            messageRep.save(MessageEntity.builder()
                    .appId(String.valueOf(appEntity.getId()))
                    .appName(appEntity.getAppName())
                    .messageTitle(pushReq.getMessageTitle())
                    .messageContent(pushReq.getMessageContent())
                    .totalCnt(0).sendSuccessCnt(0).sendFailCnt(0)
                    .platform(pushReq.getPlatform())
                    .remark("该app下没有设备需要推送").createTime(new Date())
                    .build());
            return CommonResp.defaultSuccess("该app下没有设备需要推送");
        }
        PushRequest pushRequest = new PushRequest();
        pushRequest.setMsgType(PushRequest.MessageType.DILIVERY_MSG.ordinal());
        pushRequest.setMsgContent(pushReq.getMessageContent());
        pushRequest.setFromId("admin");
        pushRequest.setToId(Lists.newArrayList(sets));
        pushRequest.setPlatform(pushReq.getPlatform());
        pushRequest.setAppKey(pushReq.getAppName());

        PushResponse pushResponse = clientDelegate.sendAndGet(pushRequest);
        if (pushResponse == null) {
            return CommonResp.defaultError("推送服务未启动");
        }
        List<Map<String, Object>> resp = pushResponse.getExtraData();
        //统计消息送达和未送达
        Long sendSuccess = resp.stream().filter(map -> (Lists.newArrayList(map.values()).get(0).equals(true))).count();
        Long sendFail = resp.stream().filter(map -> (Lists.newArrayList(map.values()).get(0).equals(false))).count();

        MessageEntity messageEntity = MessageEntity.builder()
                .appId(String.valueOf(appEntity.getId()))
                .appName(appEntity.getAppName())
                .messageTitle(pushReq.getMessageTitle())
                .messageContent(pushReq.getMessageContent())
                .totalCnt(sets.size()).sendSuccessCnt(sendSuccess.intValue()).sendFailCnt(sendFail.intValue())
                .platform(pushReq.getPlatform())
                .pushState(1)
                .remark("推送成功").createTime(new Date())
                .build();
        messageRep.save(messageEntity);
        List<DeviceMessageEntity> deviceMessageEntities = Lists.newArrayList();
        resp.forEach(obj -> obj.forEach((k, v) -> {
            String deviceId = k;
            String appName = pushReq.getAppName();
            Long appId = appEntity.getId();
            deviceMessageEntities.add(DeviceMessageEntity.builder()
                    .messageId(messageEntity.getId()).deviceId(deviceId).appId(appId).appName(appName).deliveryState(v.equals(true) ? 1 : 0).readState(0).createTime(new Date()).build());
        }));
        deviceMessageRep.save(deviceMessageEntities);
        log.info("admin push msg={}", pushRequest);
        return CommonResp.defaultSuccess();
    }

    @PostMapping("admin/list")
    public CommonResp list(PushReq pushReq) {
        String searchKey = (String) pushReq.getSearch().get("value");
        Pageable pageable = new PageRequest(pushReq.getStart() / 10, pushReq.getLength(), new Sort(Sort.Direction.DESC, "createTime"));
        Page page;
        if (StringUtils.isEmpty(searchKey)) {
            page = messageRep.findAll(pageable);
        } else {
            page = messageRep.findByAppNameLikeOrMessageContentLikeOrMessageTitleLike("%" + searchKey + "%", "%" + searchKey + "%", "%" + searchKey + "%", pageable);
        }
        CommonResp resp = CommonResp.defaultSuccess(page.iterator());
        resp.setRecordsTotal((int) page.getTotalElements());
        resp.setRecordsFiltered(resp.getRecordsTotal());
        return resp;
    }

    @PostMapping("admin/listDetail")
    public CommonResp listDetail(PushReq pushReq) {
        Pageable pageable = new PageRequest(pushReq.getStart() / 10, pushReq.getLength(), new Sort(Sort.Direction.DESC, "createTime"));
        Page page = deviceMessageRep.findAll(Example.of(DeviceMessageEntity.builder().messageId(pushReq.getMsgId().longValue()).deliveryState(pushReq.getType()).build()), pageable);
        CommonResp resp = CommonResp.defaultSuccess(page.iterator());
        resp.setRecordsTotal((int) page.getTotalElements());
        resp.setRecordsFiltered(resp.getRecordsTotal());
        return resp;
    }

    @PostMapping("admin/chartInfo")
    public CommonResp chartInfo(StaticsReq staticsReq) {
        List<String> triggerDayList = new ArrayList<String>();
        List<Integer> triggerDayCountRunningList = new ArrayList<Integer>();
        List<Integer> triggerDayCountSucList = new ArrayList<Integer>();
        List<Integer> triggerDayCountFailList = new ArrayList<Integer>();
        int triggerCountRunningTotal = 0;
        int triggerCountSucTotal = 0;
        int triggerCountFailTotal = 0;

        List<MessageEntity> logReportList = messageRep.findByCreateTimeBetween(DateUtil.parseDateTime(staticsReq.getStartDate()),
                DateUtil.parseDateTime(staticsReq.getEndDate()));

        if (logReportList != null && logReportList.size() > 0) {
            for (MessageEntity item : logReportList) {
                String day = DateUtil.formatDate(item.getCreateTime());
                int triggerDayCountRunning = item.getTotalCnt();
                int triggerDayCountSuc = item.getSendSuccessCnt();
                int triggerDayCountFail = item.getSendFailCnt();

                triggerDayList.add(day);
                triggerDayCountRunningList.add(triggerDayCountRunning);
                triggerDayCountSucList.add(triggerDayCountSuc);
                triggerDayCountFailList.add(triggerDayCountFail);

                triggerCountRunningTotal += triggerDayCountRunning;
                triggerCountSucTotal += triggerDayCountSuc;
                triggerCountFailTotal += triggerDayCountFail;
            }
        } else {
            for (int i = -6; i <= 0; i++) {
                triggerDayList.add(DateUtil.formatDate(DateUtil.addDays(new Date(), i)));
                triggerDayCountRunningList.add(0);
                triggerDayCountSucList.add(0);
                triggerDayCountFailList.add(0);
            }
        }

        Map<String, Object> result = new HashMap<String, Object>();
        result.put("triggerDayList", triggerDayList);
        result.put("triggerDayCountRunningList", triggerDayCountRunningList);
        result.put("triggerDayCountSucList", triggerDayCountSucList);
        result.put("triggerDayCountFailList", triggerDayCountFailList);

        result.put("triggerCountRunningTotal", triggerCountRunningTotal);
        result.put("triggerCountSucTotal", triggerCountSucTotal);
        result.put("triggerCountFailTotal", triggerCountFailTotal);

        return CommonResp.defaultSuccess(result);
    }
}
