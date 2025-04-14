package ca.lwi.trqcbot.utils;

import org.bson.Document;

public class TeamSelectionResult {

    public Document team;
    public boolean isTrade;
    public String originalTeamName;
    public String originalTeamLogo;

    public TeamSelectionResult(Document team, boolean isTrade, String originalTeamName, String originalTeamLogo) {
        this.team = team;
        this.isTrade = isTrade;
        this.originalTeamName = originalTeamName;
        this.originalTeamLogo = originalTeamLogo;
    }
}