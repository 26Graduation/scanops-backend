package com.scanops.team;

public enum TeamRole {
    /** 팀 생성자. 결제 주체(subscriptions.user_id)이자 유일하게 탈퇴할 수 없는 멤버. */
    OWNER,
    /** 멤버 초대/제거 가능. */
    ADMIN,
    MEMBER
}
