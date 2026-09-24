package com.yunjihai.rag.identity;

import java.util.List;

public interface DingTalkClient {

    ExternalIdentity exchangeAuthorizationCode(String code);

    List<ExternalDepartment> listDepartments();

    List<ExternalUser> listUsers(String departmentExternalId);
}
