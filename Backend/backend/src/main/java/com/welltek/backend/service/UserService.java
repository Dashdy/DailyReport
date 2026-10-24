package com.welltek.backend.service;

import com.welltek.backend.config.BizException;
import com.welltek.backend.dto.Requests;
import com.welltek.backend.entity.ProcessStep;
import com.welltek.backend.entity.Role;
import com.welltek.backend.entity.User;
import com.welltek.backend.mapper.ProcessMapper;
import com.welltek.backend.mapper.UserMapper;
import com.welltek.backend.security.LoginUser;
import com.welltek.backend.security.TokenStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

// 【用户管理】
// 规则：
// - 只有系统管理员、管理员能进来（Controller 上有 @RequireAdmin）；
// - 系统管理员账号只有系统管理员能新增 / 修改 / 删除，管理员也不能把别人提升成系统管理员；
// - 不能删除、停用、降级自己；系统里至少保留一个启用的系统管理员。
@Service
public class UserService {

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private ProcessMapper processMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private TokenStore tokenStore;

    @Autowired
    private AuditService auditService;

    public List<Map<String, Object>> list() {
        Map<Integer, String> processNames = processNames();
        Map<Integer, List<Integer>> owned = userMapper.selectAllProcessIds();
        List<Map<String, Object>> result = new ArrayList<>();
        for (User u : userMapper.selectAll()) {
            List<Integer> ids = owned.getOrDefault(u.id(), List.of());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", u.id());
            m.put("account", u.account());
            m.put("username", u.username());
            m.put("role", u.role().name());
            m.put("roleLabel", u.role().getLabel());
            m.put("enabled", u.enabled());
            m.put("remark", u.remark());
            m.put("wecomUserId", u.wecomUserId());
            m.put("processIds", ids);
            m.put("processNames", ids.stream().map(processNames::get).filter(Objects::nonNull).toList());
            m.put("createdAt", u.createdAt());
            m.put("updatedAt", u.updatedAt());
            result.add(m);
        }
        return result;
    }

    @Transactional
    public void create(LoginUser op, Requests.SaveUser req) {
        String account = checkAccount(req.account());
        String username = requireText(req.username(), "请填写用户名");
        Role role = parseRole(req.role());
        if (role == Role.SYS_ADMIN && !op.isSysAdmin()) {
            throw new BizException("只有系统管理员可以创建系统管理员账号");
        }
        if (userMapper.selectByAccount(account) != null) {
            throw new BizException("账号“" + account + "”已被占用");
        }
        String password = checkPassword(req.password());
        boolean enabled = req.enabled() == null || req.enabled();

        Integer id = userMapper.insert(account, username, passwordEncoder.encode(password), role, enabled, trim(req.remark()));
        userMapper.updateWecomUserId(id, trim(req.wecomUserId()));
        List<Integer> processIds = req.processIds() == null ? List.of() : req.processIds();
        userMapper.replaceProcesses(id, processIds);

        auditService.log(op, AuditService.MODULE_USER, "新增", null, "用户 " + username + "（" + account + "）", null,
                describe(account, username, role, enabled, trim(req.remark()), processIds));
    }

    @Transactional
    public void update(LoginUser op, Integer id, Requests.SaveUser req) {
        User old = requireUser(id);
        checkCanManage(op, old);

        String account = checkAccount(req.account());
        String username = requireText(req.username(), "请填写用户名");
        Role role = parseRole(req.role());
        boolean enabled = req.enabled() == null || req.enabled();

        if (role == Role.SYS_ADMIN && !op.isSysAdmin()) {
            throw new BizException("只有系统管理员可以设置系统管理员角色");
        }
        if (Objects.equals(op.id(), id) && (role != old.role() || !enabled)) {
            throw new BizException("不能修改自己的角色或停用自己");
        }
        User sameAccount = userMapper.selectByAccount(account);
        if (sameAccount != null && !sameAccount.id().equals(id)) {
            throw new BizException("账号“" + account + "”已被占用");
        }
        if (old.role() == Role.SYS_ADMIN && old.enabled() && (role != Role.SYS_ADMIN || !enabled)) {
            ensureAnotherSysAdmin();
        }

        List<Integer> oldProcessIds = new ArrayList<>(userMapper.selectProcessIds(id));
        List<Integer> processIds = req.processIds() == null ? oldProcessIds : req.processIds();

        userMapper.update(id, account, username, role, enabled, trim(req.remark()));
        userMapper.updateWecomUserId(id, trim(req.wecomUserId()));
        userMapper.replaceProcesses(id, processIds);
        boolean passwordChanged = req.password() != null && !req.password().isBlank();
        if (passwordChanged) {
            userMapper.updatePassword(id, passwordEncoder.encode(checkPassword(req.password())));
        }
        // 停用、改密码、改账号后，旧的登录全部失效，需要用新信息重新登录
        if (!enabled || passwordChanged || !account.equals(old.account())) {
            tokenStore.invalidateUser(id);
        }

        Map<String, Object> after = describe(account, username, role, enabled, trim(req.remark()), processIds);
        after.put("企业微信账号", nvl(trim(req.wecomUserId())));
        if (passwordChanged) {
            after.put("密码", "已重置");
        }
        Map<String, Object> before = describe(old.account(), old.username(), old.role(), old.enabled(), old.remark(), oldProcessIds);
        before.put("企业微信账号", nvl(old.wecomUserId()));
        auditService.log(op, AuditService.MODULE_USER, "修改", null, "用户 " + old.username() + "（" + old.account() + "）", before, after);
    }

    @Transactional
    public void delete(LoginUser op, Integer id) {
        User old = requireUser(id);
        checkCanManage(op, old);
        if (Objects.equals(op.id(), id)) {
            throw new BizException("不能删除自己");
        }
        if (old.role() == Role.SYS_ADMIN && old.enabled()) {
            ensureAnotherSysAdmin();
        }
        List<Integer> oldProcessIds = new ArrayList<>(userMapper.selectProcessIds(id));
        tokenStore.invalidateUser(id);
        userMapper.delete(id);
        auditService.log(op, AuditService.MODULE_USER, "删除", null, "用户 " + old.username() + "（" + old.account() + "）",
                describe(old.account(), old.username(), old.role(), old.enabled(), old.remark(), oldProcessIds), null);
    }

    @Transactional
    public void resetPassword(LoginUser op, Integer id, String password) {
        User user = requireUser(id);
        checkCanManage(op, user);
        userMapper.updatePassword(id, passwordEncoder.encode(checkPassword(password)));
        tokenStore.invalidateUser(id);
        auditService.log(op, AuditService.MODULE_USER, "重置密码", null, "用户 " + user.username() + "（" + user.account() + "）", null, "密码已重置");
    }

    /** 工序分配页：按用户整体设置他负责的工序 */
    @Transactional
    public void assignProcesses(LoginUser op, Integer id, List<Integer> processIds) {
        User user = requireUser(id);
        List<Integer> oldIds = new ArrayList<>(userMapper.selectProcessIds(id));
        List<Integer> newIds = processIds == null ? List.of() : processIds;
        userMapper.replaceProcesses(id, newIds);
        Map<Integer, String> names = processNames();
        auditService.log(op, AuditService.MODULE_ASSIGN, "修改", null, "用户 " + user.username() + " 的负责工序",
                joinNames(oldIds, names), joinNames(newIds, names));
    }

    /** 本人修改自己的密码（用户名、账号只能由管理员改，密码本人也可以改） */
    public void changeOwnPassword(LoginUser op, String oldPassword, String newPassword) {
        User user = requireUser(op.id());
        if (oldPassword == null || !passwordEncoder.matches(oldPassword, user.password())) {
            throw new BizException("原密码不正确");
        }
        userMapper.updatePassword(op.id(), passwordEncoder.encode(checkPassword(newPassword)));
        auditService.log(op, AuditService.MODULE_USER, "修改密码", null,
                "用户 " + user.username() + "（" + user.account() + "）", null, "本人修改了密码");
    }

    // ---------------- 内部校验 ----------------

    /** 账号：登录用，必填、唯一，不能有空格 */
    private String checkAccount(String account) {
        String a = requireText(account, "请填写账号");
        if (a.chars().anyMatch(Character::isWhitespace)) {
            throw new BizException("账号不能包含空格");
        }
        return a;
    }

    private void checkCanManage(LoginUser op, User target) {
        if (target.role() == Role.SYS_ADMIN && !op.isSysAdmin()) {
            throw new BizException("只有系统管理员可以修改系统管理员账号");
        }
    }

    private void ensureAnotherSysAdmin() {
        if (userMapper.countEnabledByRole(Role.SYS_ADMIN) <= 1) {
            throw new BizException("系统里至少要保留一个启用的系统管理员");
        }
    }

    private User requireUser(Integer id) {
        User user = userMapper.selectById(id);
        if (user == null) {
            throw new BizException("用户不存在，可能已被删除");
        }
        return user;
    }

    private Role parseRole(String role) {
        try {
            return Role.valueOf(role);
        } catch (Exception e) {
            throw new BizException("请选择角色");
        }
    }

    private String checkPassword(String password) {
        if (password == null || password.length() < 4) {
            throw new BizException("密码至少 4 位");
        }
        return password;
    }

    private static String requireText(String s, String message) {
        if (s == null || s.isBlank()) {
            throw new BizException(message);
        }
        return s.trim();
    }

    private static String nvl(String s) {
        return s == null ? "" : s;
    }

    private static String trim(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private Map<Integer, String> processNames() {
        return processMapper.selectAll(true).stream()
                .collect(Collectors.toMap(ProcessStep::id, ProcessStep::name));
    }

    private Map<String, Object> describe(String account, String username, Role role, boolean enabled, String remark, List<Integer> processIds) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("账号", account);
        m.put("用户名", username);
        m.put("角色", role.getLabel());
        m.put("状态", enabled ? "启用" : "停用");
        m.put("备注", remark == null ? "" : remark);
        m.put("负责工序", joinNames(processIds, processNames()));
        return m;
    }

    private static String joinNames(Collection<Integer> ids, Map<Integer, String> names) {
        String s = ids.stream().map(names::get).filter(Objects::nonNull).collect(Collectors.joining("、"));
        return s.isEmpty() ? "（无）" : s;
    }
}
