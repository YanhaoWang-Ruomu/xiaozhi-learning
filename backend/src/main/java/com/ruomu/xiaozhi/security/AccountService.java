package com.ruomu.xiaozhi.security;

import com.mongodb.MongoWriteException;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Locale;
import java.util.UUID;
import static com.mongodb.client.model.Filters.eq;

@Service
public class AccountService implements UserDetailsService {
    private final MongoCollection<Document> users;
    private final PasswordEncoder encoder;
    public AccountService(MongoTemplate mongo, PasswordEncoder encoder) {
        users = mongo.getCollection("auth_users");
        users.createIndex(Indexes.ascending("username"), new IndexOptions().unique(true));
        this.encoder = encoder;
    }
    public AccountUser.View register(String username, String password) {
        String name = normalize(username);
        if (!name.matches("[a-z0-9_]{3,32}")) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "用户名须为3到32位英文字母、数字或下划线");
        validatePassword(password);
        AccountUser user = new AccountUser(UUID.randomUUID().toString(), name, encoder.encode(password));
        try {
            users.insertOne(new Document("_id", user.userId()).append("username", name)
                    .append("passwordHash", user.passwordHash()).append("createdAt", new Date()));
        } catch (MongoWriteException e) {
            if (e.getError().getCode() == 11000) throw new ResponseStatusException(HttpStatus.CONFLICT, "用户名已存在");
            throw e;
        }
        return user.view();
    }
    public static String normalize(String username) { return username == null ? "" : username.strip().toLowerCase(Locale.ROOT); }
    public static void validatePassword(String password) {
        if (password == null || password.length() < 12 || password.getBytes(StandardCharsets.UTF_8).length > 72)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "密码至少12个字符，UTF-8长度最多72字节");
    }
    @Override public AccountUser loadUserByUsername(String username) {
        Document row = users.find(eq("username", normalize(username))).first();
        if (row == null) throw new UsernameNotFoundException("账号或密码错误");
        return new AccountUser(row.getString("_id"), row.getString("username"), row.getString("passwordHash"));
    }
}
