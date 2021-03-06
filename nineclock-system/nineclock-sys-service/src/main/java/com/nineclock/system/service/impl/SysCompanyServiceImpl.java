package com.nineclock.system.service.impl;
import com.alibaba.fastjson.JSON;
import com.google.common.collect.Lists;

import java.util.Arrays;
import java.util.Collections;
import java.util.Date;


import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.nineclock.common.constant.NcConstant;
import com.nineclock.common.constant.SMSConstant;
import com.nineclock.common.enums.MessageTypeEnum;
import com.nineclock.common.enums.ResponseEnum;
import com.nineclock.common.exception.NcException;
import com.nineclock.common.filter.CurrentUserHolder;
import com.nineclock.common.oss.OssClientUtils;
import com.nineclock.common.utils.BeanHelper;
import com.nineclock.system.dto.*;
import com.nineclock.system.im.HxImManager;
import com.nineclock.system.mapper.*;
import com.nineclock.system.message.MessageDTO;
import com.nineclock.system.pojo.*;
import com.nineclock.system.service.SysCompanyService;
import com.nineclock.system.service.SysUserService;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
@Service
@Transactional
@Slf4j
public class SysCompanyServiceImpl implements SysCompanyService {

    @Autowired
    private SysCompanayMapper sysCompanayMapper;

    @Autowired
    private OssClientUtils ossClientUtils;


    @Autowired
    private SysCompanayUserMapper companayUserMapper;

    @Autowired
    private RedisTemplate<String,String> redisTemplate;


    @Autowired
    private SysRoleMapper roleMapper;
    @Autowired
    private SysCompanyUserRoleMapper companyUserRoleMapper;

    @Autowired
    private SysUserService userService;

    @Autowired

    private SysUserMapper userMapper;
    //RocketMQ
    @Autowired
    private RocketMQTemplate rocketMQTemplate;


    @Autowired
    private HxImManager hxImManager;



    @Override
    public List<SysCompanyDTO> findCompanyByUserId(Long userId) {
        // ????????????
        if (userId == null) {
            throw new NcException(ResponseEnum.INVALID_PARAM_ERROR);
        }

        // ????????????id???????????????????????????
        List<SysCompany> sysCompanyList = sysCompanayMapper.queryUserJoinCompany(userId);
        if (CollectionUtils.isEmpty(sysCompanyList)) {
            // ???????????????????????????????????????????????????
            throw new NcException(ResponseEnum.USER_NOT_JOIN_COMPANY);

        }

        // ????????????
        List<SysCompanyDTO> companyDTOList = BeanHelper.copyWithCollection(sysCompanyList, SysCompanyDTO.class);

        return companyDTOList;
    }


    @Override
    public SysCompanyDTO queryCompanyInfo()  {
        // ????????????????????????id
        Long companyId = CurrentUserHolder.get().getCompanyId();

        // ??????id??????????????????
        SysCompany sysCompany = sysCompanayMapper.selectById(companyId);

        if(sysCompany == null){
            throw new NcException(ResponseEnum.COMPANY_NOT_FOUND);
        }

        return BeanHelper.copyProperties(sysCompany, SysCompanyDTO.class);
    }



    @Override
    public String uploadOSS(MultipartFile file) {
        //????????????
        if (file == null){
            throw new NcException(ResponseEnum.INVALID_FILE_TYPE);
        }

        //??????????????????
        String contentType = file.getContentType();
        //"image/jpeg", "image/bmp", "image/png"; //?????????????????????
        if (!NcConstant.ALLOWED_IMG_TYPES.contains(contentType)){
            throw new NcException(ResponseEnum.INVALID_FILE_TYPE);
        }
        //??????????????????
        if (file.getSize()>NcConstant.maxFileSize){
            throw new NcException(ResponseEnum.FILE_SIZE_EXCEED_MAX_LIMIT);
        }

        //4.??????oss?????????????????????
        String flag = null;
        try {
            flag = ossClientUtils.uploadFile(file.getOriginalFilename(), file.getInputStream());
        } catch (IOException e) {
            e.printStackTrace();
        }
        return flag;
    }

    @Override
    public void updateCompanyInfo(SysCompanyDTO sysCompanyDTO) {
        // ????????????
        if (sysCompanyDTO == null) {
            throw new NcException(ResponseEnum.INVALID_PARAM_ERROR);
        }
        //???????????????????????????id
        Long companyId = CurrentUserHolder.get().getCompanyId();

        //?????????????????????
        SysCompany sysCompany = new SysCompany();
        //???????????????????????? ?????????logo??????
        sysCompany.setName(sysCompanyDTO.getName());
        sysCompany.setLogo(sysCompanyDTO.getLogo());
        //??????????????????id ?????????
        sysCompany.setId(companyId);
        sysCompanayMapper.updateById(sysCompany);


        //??????????????? ???????????????????????????
        SysCompanyUser sysCompanyUser = new SysCompanyUser();
        //????????????????????? ????????????????????????
        sysCompanyUser.setCompanyName(sysCompanyDTO.getName());

        LambdaQueryWrapper<SysCompanyUser> wrapper = new LambdaQueryWrapper<>();

        wrapper.eq(SysCompanyUser::getCompanyId,companyId);

        companayUserMapper.update(sysCompanyUser,wrapper);

    }

    /**
     * ????????????: ?????????????????????
     */
    @Override
    public void changeSysAdmin(String code, Long userId) {
        // ????????????
        if(StringUtils.isEmpty(code) || userId == null){
            throw new NcException(ResponseEnum.INVALID_PARAM_ERROR);
        }
        //???????????????userId ?????? ?????????
        SysCompanyUser companyUser = companayUserMapper.selectById(userId);
        //???redis???????????????????????????                   ////????????????
        String redisCode = redisTemplate.opsForValue().get(SMSConstant.SMS_CHANGE_MANAGER_KEY_PREFIX + companyUser.getMobile());

        if (StringUtils.isEmpty(redisCode)||!redisCode.equals(code)){
            throw new NcException(ResponseEnum.INVALID_VERIFY_CODE);
        }

        //????????????????????????????????????
        SysCompanyUserDTO currentAdmin = this.getCurrentAdmin();


        // ??????????????????????????????id
        LambdaQueryWrapper<SysRole> wrapper= new LambdaQueryWrapper<>();
        wrapper.eq(SysRole::getCompanyId,currentAdmin.getCompanyId()); //??????????????????id

        wrapper.eq(SysRole::getRoleName, NcConstant.ADMIN_ROLE_SYS); //???????????????????????????
        SysRole sysRole = roleMapper.selectOne(wrapper);

        // ???????????????
        LambdaQueryWrapper<SysCompanyUserRole> wrapper1= new LambdaQueryWrapper<>();

        //????????????????????????
        wrapper1.eq(SysCompanyUserRole::getCompanyUserId,currentAdmin.getId()); //????????????id
        wrapper1.eq(SysCompanyUserRole::getRoleId,sysRole.getId()); //???????????????id



        SysCompanyUserRole sysCompanyUserRole =new SysCompanyUserRole();
        sysCompanyUserRole.setCompanyUserId(userId);

        companyUserRoleMapper.update(sysCompanyUserRole,wrapper1);

    }

    private SysCompanyUserDTO getCurrentAdmin() {
        //???????????????????????????id
        Long companyId = CurrentUserHolder.get().getCompanyId();
        //??????id??????????????????
        SysCompanyUser sysCompanyUser = companayUserMapper.queryAdminCompanyUser(companyId);


    return BeanHelper.copyProperties(sysCompanyUser,SysCompanyUserDTO.class);

    }
    /**
     *  ?????????????????????????????????  like
     */
    @Override
    public List<SysCompanyDTO> queryCompanyByName(String keyword) {
        LambdaQueryWrapper<SysCompany> wrapper =new LambdaQueryWrapper<>();
        //???????????????
        wrapper.like(SysCompany::getName,keyword);
        List<SysCompany> companyList = sysCompanayMapper.selectList(wrapper);
        return  BeanHelper.copyWithCollection(companyList, SysCompanyDTO.class);

    }

    /**
     * ??????????????????
     */
    @Override
    public void applyJoinCompany(SysApplyJoinCompanyUserDTO applyJoinCompanyUserDTO) {
        // ????????????
        if(applyJoinCompanyUserDTO == null || applyJoinCompanyUserDTO.getMobile() == null){
            throw new NcException(ResponseEnum.INVALID_PARAM_ERROR);
        }

        //??????userService ????????????????????????????????? ???????????? sysUser
        SysUserDTO userDTO = userService.queryUser(applyJoinCompanyUserDTO.getMobile());

        SysUser sysUser = BeanHelper.copyProperties(userDTO, SysUser.class);

        sysUser.setUsername(applyJoinCompanyUserDTO.getUserName()); //???????????????
        sysUser.setEmail(applyJoinCompanyUserDTO.getEmail());  //??????
        sysUser.setUpdateTime(new Date()); //??????
        sysUser.setLastLoginCompanyId(applyJoinCompanyUserDTO.getCompanyId()); //??????id
        //??????????????????
        userMapper.updateById(sysUser);


        // ??????????????????
        SysCompanyUser sysCompanyUser = new SysCompanyUser();
        sysCompanyUser.setUserId(userDTO.getId());
        sysCompanyUser.setCompanyId(applyJoinCompanyUserDTO.getCompanyId());
        sysCompanyUser.setCompanyName(applyJoinCompanyUserDTO.getCompanyName());
        sysCompanyUser.setPost(applyJoinCompanyUserDTO.getPost());
        sysCompanyUser.setEmail(applyJoinCompanyUserDTO.getEmail());
        sysCompanyUser.setTimeEntry(new Date());
        sysCompanyUser.setRemark(applyJoinCompanyUserDTO.getApplyReason());
        sysCompanyUser.setEnable((short)0); // ?????????
        sysCompanyUser.setCreateTime(new Date());
        sysCompanyUser.setUpdateTime(new Date());
        sysCompanyUser.setMobile(applyJoinCompanyUserDTO.getMobile());
        sysCompanyUser.setUserName(applyJoinCompanyUserDTO.getUserName());
        sysCompanyUser.setImageUrl(applyJoinCompanyUserDTO.getImageUrl());

        //?????????????????????
        companayUserMapper.insert(sysCompanyUser);


        //???????????????
        //??????1??????????????????????????? ?????????2???????????????????????????JSON????????????
        MessageDTO messageDTO =new MessageDTO();
        messageDTO.setMessageType(MessageTypeEnum.COMPANY_APPLY.getType()); // ????????????
        messageDTO.setCompanyId(sysCompanyUser.getCompanyId().toString()); // ??????id
        messageDTO.setTitle(MessageTypeEnum.COMPANY_APPLY.getTitle()); // ??????
        messageDTO.setContent(sysCompanyUser.getUserName() + " ????????????????????????????????????"); // ??????
        messageDTO.setUseStatus(0); // ??????
        messageDTO.setAudience(MessageTypeEnum.COMPANY_APPLY.getAudience()); // ???????????????

        // ??????????????????????????????
        SysCompanyUser mainAdmin = companayUserMapper.queryAdminCompanyUser(sysCompanyUser.getCompanyId());
        messageDTO.setTargets(Arrays.asList(mainAdmin.getMobile())); // ???????????????????????????

        messageDTO.setApproveStatue(0); // ?????????
        messageDTO.setApplyUserId(sysCompanyUser.getUserId()); // ????????????ID
        messageDTO.setApplyUsername(sysCompanyUser.getUserName()); // ??????????????????

        messageDTO.setApproveUserId(mainAdmin.getUserId()); // ????????????id
        messageDTO.setApproveUsername(mainAdmin.getUserName()); // ??????????????????

        rocketMQTemplate.convertAndSend("messagePushTopic", JSON.toJSONString(messageDTO));


        // ????????????????????????, ??????APP??????????????????????????????
        sysUser.setPassword("123456");
        sysUser.setUsername(sysUser.getMobile());//?????????
        hxImManager.registerUser2HuanXing(sysUser);
        log.info("????????????????????????");
    }

    /**
     * ??????????????????
     */
    @Override
    public void allowedJoinCompany(SysAllowedJoinCompanyUserDTO sysAllowedJoinCompanyUserDTO) {

        //????????????ID?????????ID??????????????????
        LambdaQueryWrapper<SysCompanyUser> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SysCompanyUser::getCompanyId, CurrentUserHolder.get().getCompanyId()); // ??????id
        wrapper.eq(SysCompanyUser::getUserId, sysAllowedJoinCompanyUserDTO.getApplyUserId());//?????????id

        SysCompanyUser sysCompanyUser = companayUserMapper.selectOne(wrapper);


        //???????????? ???????????? ??? 0??????1
        if (sysCompanyUser !=null){
            sysCompanyUser.setRemark(sysAllowedJoinCompanyUserDTO.getRemark()); //??????
                                                                //???????????? ?????????true ????????????1
            sysCompanyUser.setEnable(sysAllowedJoinCompanyUserDTO.getApproved()?(short)1:(short)0);

            companayUserMapper.updateById(sysCompanyUser);
            if (sysAllowedJoinCompanyUserDTO.getApproved()){ //?????????true ????????????
                //???????????????MQ, ??????????????????????????????????????? ??????????????????;
                rocketMQTemplate.convertAndSend("allowedJoinCompanyTopic",
                        sysAllowedJoinCompanyUserDTO.getNotifyMsgId());//??????????????????ID
                //???????????????MQ?????????????????????????????????????????????
                MessageDTO messageDTO = new MessageDTO();


                messageDTO.setContent(sysCompanyUser.getUserName()+"????????????????????????");

                messageDTO.setTargets(Collections.singletonList(sysCompanyUser.getMobile()));

                messageDTO.setApplyUserId(sysAllowedJoinCompanyUserDTO.getApplyUserId());
                messageDTO.setApplyUsername(sysCompanyUser.getUserName());

                log.info("???????????????MQ????????????????????????????????????????????????");
                rocketMQTemplate.convertAndSend("messagePushTopic",messageDTO);

            }
        }else {
            //????????????
            //??????????????????
            LambdaQueryWrapper<SysCompanyUser> wrapper1 = new LambdaQueryWrapper<>();
            wrapper1.eq(SysCompanyUser::getCompanyId, CurrentUserHolder.get().getCompanyId()); //??????id
            wrapper1.eq(SysCompanyUser::getUserId, sysAllowedJoinCompanyUserDTO.getApplyUserId()); //?????????ID
            companayUserMapper.delete(wrapper1);
        }


    }

}
