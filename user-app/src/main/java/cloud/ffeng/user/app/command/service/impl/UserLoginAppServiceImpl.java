package cloud.ffeng.user.app.command.service.impl;

import cloud.ffeng.cat.common.util.AssertUtil;
import cloud.ffeng.cat.common.util.FuncUtil;
import cloud.ffeng.user.app.command.assembler.UserLoginAssembler;
import cloud.ffeng.user.app.command.service.UserLoginAppService;
import cloud.ffeng.user.app.support.TxHelper;
import cloud.ffeng.user.domain.base.component.CacheHelper;
import cloud.ffeng.user.domain.platform.aggregate.PlatformAuthFlow;
import cloud.ffeng.user.domain.platform.entity.PlatformUser;
import cloud.ffeng.user.domain.platform.repository.PlatformAuthRepository;
import cloud.ffeng.user.domain.user.aggregate.UserLoginFlow;
import cloud.ffeng.user.domain.user.entity.UserInfo;
import cloud.ffeng.user.domain.user.repository.UserInfoRepository;
import cloud.ffeng.user.domain.user.service.UserDomainService;
import cloud.ffeng.user.domain.user.service.UserLoginDomainService;
import cloud.ffeng.user.domain.verification.service.VerificationDomainService;
import cloud.ffeng.user.facade.dto.UserLoginFlowDTO;
import cloud.ffeng.user.facade.request.UserLoginByPasswordRequest;
import cloud.ffeng.user.facade.request.UserLoginByPlatformAuthRequest;
import cloud.ffeng.user.facade.request.UserLoginByVerificationRequest;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import static cloud.ffeng.user.common.constants.BizStatusConstants.*;
import static cloud.ffeng.user.common.enums.UserLoginMethodEnum.PASSWORD;
import static cloud.ffeng.user.domain.base.enums.LockKey.PLATFORM_AUTH;
import static cloud.ffeng.user.domain.base.enums.LockKey.USER_LOGIN;

/**
 * @author cat-feng
 */
@Slf4j
@Service
@AllArgsConstructor
public class UserLoginAppServiceImpl implements UserLoginAppService {

    private final UserInfoRepository userInfoRepository;
    private final PlatformAuthRepository platformAuthRepository;
    private final UserLoginDomainService userLoginDomainService;
    private final UserDomainService userDomainService;
    private final VerificationDomainService verificationDomainService;
    private final TransactionTemplate transactionTemplate;

    @Override
    public UserLoginFlowDTO loginByPassword(UserLoginByPasswordRequest request) {
        // 验证码拦截：确认为真人操作。
        boolean verificationFlag = verificationDomainService.validateCode(request.getVerificationFlowId(), request.getVerificationCode());
        AssertUtil.isTrue(verificationFlag, U_VERIFICATION_CHECK_FAILED, "验证码错误，请刷新验证码后重试！");

        // 校验用户名
        UserInfo userInfo = userInfoRepository.getByLoginKey(request.getLoginKey());
        AssertUtil.nonNull(userInfo, U_USER_NOT_EXIST, "用户名或密码错误！");

        // 校验密码
        boolean checkPasswordFlag = userInfo.passwordIsValid(request.getPasswordEncrypted());
        AssertUtil.isTrue(checkPasswordFlag, U_USER_PASSWORD_CHECK_FAILED, "用户名或密码错误！");

        // eg: 编程式事务
        return TxHelper.exec(() -> {
            // 执行登录逻辑：生成流水 + Session信息
            boolean userLoginLock = CacheHelper.lock(USER_LOGIN, userInfo.getUserId().toString());
            AssertUtil.isTrue(userLoginLock, U_USER_LOGIN_CONCURRENTLY, "用户同时登录不被许可！");

            // 用户开始执行登录操作
            UserLoginFlow userLoginFlow = userLoginDomainService.start(userInfo.getUserId(), PASSWORD);

            // 执行登录操作：生成登录记录&会话信息，返回本地登录的会话、用户等信息
            userLoginFlow = userLoginDomainService.doLogin(userLoginFlow);

            // 构建登录结果信息
            return UserLoginAssembler.toDTO(userLoginFlow);
        });
    }

    @Override
    @Transactional(rollbackFor = Throwable.class)
    public UserLoginFlowDTO loginByVerification(UserLoginByVerificationRequest request) {
        // 验证码拦截：确认为真人操作。
        boolean verificationFlag = verificationDomainService.validateCode(request.getVerificationFlowId(), request.getVerificationCode());
        AssertUtil.isTrue(verificationFlag, U_VERIFICATION_CHECK_FAILED, "验证码错误，请刷新验证码后重试！");

        // 校验用户名
        UserInfo userInfo = userInfoRepository.getByLoginKey(request.getLoginKey());
        AssertUtil.nonNull(userInfo, U_USER_NOT_EXIST, "用户名或密码错误！");

        // 执行登录逻辑：生成流水 + Session信息
        boolean userLoginLock = CacheHelper.lock(USER_LOGIN, userInfo.getUserId().toString());
        AssertUtil.isTrue(userLoginLock, U_USER_LOGIN_CONCURRENTLY, "用户同时登录不被许可！");

        // 用户开始执行登录操作
        UserLoginFlow userLoginFlow = userLoginDomainService.start(userInfo.getUserId(), PASSWORD);

        // 执行登录操作：生成登录记录&会话信息，返回本地登录的会话、用户等信息
        userLoginFlow = userLoginDomainService.doLogin(userLoginFlow);

        // 构建登录结果信息
        return UserLoginAssembler.toDTO(userLoginFlow);
    }

    @Override
    @Transactional(rollbackFor = Throwable.class)
    public UserLoginFlowDTO loginByPlatformAuth(UserLoginByPlatformAuthRequest request) {
        // 确认登录的授权流水存在
        PlatformAuthFlow platformAuthFlow = platformAuthRepository.get(request.getPlatformAuthFlowId());
        AssertUtil.nonNull(platformAuthFlow, U_PLATFORM_AUTH_FLOW_NOT_EXIST, "授权流程信息不存在，请重试！");

        // 执行授权：
        PlatformUser platformUser = new PlatformUser();
        boolean platformAuth = CacheHelper.lock(PLATFORM_AUTH, platformUser.uniqueKey());
        AssertUtil.isTrue(platformAuth, U_USER_LOGIN_CONCURRENTLY, "用户同时授权不被许可！");

        // 外部平台用户查询对应用户信息
        UserInfo userInfo = userInfoRepository.get(platformUser.getPlatform(), platformUser.getPlatformUserId());
        // 如果外部平台用户第一次登录就默认执行创建新用户逻辑
        userInfo = FuncUtil.nullGet(userInfo, () -> userDomainService.createNewUser(platformUser));

        // 用户开始执行登录操作
        UserLoginFlow userLoginFlow = userLoginDomainService.start(userInfo.getUserId(), PASSWORD);

        // 执行登录操作：生成登录记录&会话信息，返回本地登录的会话、用户等信息
        userLoginFlow = userLoginDomainService.doLogin(userLoginFlow);

        // 构建登录结果信息
        return UserLoginAssembler.toDTO(userLoginFlow);
    }

}
