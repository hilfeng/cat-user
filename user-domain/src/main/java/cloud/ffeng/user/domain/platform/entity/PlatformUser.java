package cloud.ffeng.user.domain.platform.entity;

import lombok.Getter;

/**
 * @author cat-feng
 */
@Getter
public class PlatformUser {
    private String platformUserId;

    private String platform;
    private String nickname;

    public String uniqueKey() {
        return platform + platformUserId;
    }
}
