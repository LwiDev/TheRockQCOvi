package ca.lwi.trqcbot.mongo;

import lombok.Data;

@Data
public class MongoCredentials {

    private final String ip;
    private final String password;
    private final String username;
    private final String database;

}
