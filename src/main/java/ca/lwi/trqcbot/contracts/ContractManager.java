package ca.lwi.trqcbot.contracts;

import ca.lwi.trqcbot.Main;
import ca.lwi.trqcbot.utils.ImageUtils;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.components.buttons.Button;
import net.dv8tion.jda.api.utils.FileUpload;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.jetbrains.annotations.NotNull;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

public class ContractManager extends ListenerAdapter {

    private static final int ENTRY_CONTRACT_DURATION_DAYS = 3; // 3 ans
    private static final int MAX_CONTRACT_YEARS = 8; // 8 ans max
    private static final int DAYS_PER_YEAR = 4; // 4 jours par année
    private static final int CONTRACT_RESPONSE_DEADLINE_DAYS = 3; // 3 jours pour répondre

    // Limites de salaire
    private static final int MIN_ENTRY_SALARY = 775000; // 775,000$ minimum
    private static final int MAX_ENTRY_SALARY = 950000; // 950,000$ maximum
    private static final int MIN_SALARY = 775000; // 775,000$ minimum
    private static final int MAX_SALARY = 14000000; // 14M$ maximum

    private static final int TWO_WAY_REPUTATION_THRESHOLD = 30;

    private final ScheduledExecutorService scheduler;

    public ContractManager() {
        this.scheduler = Executors.newScheduledThreadPool(1);
        scheduler.scheduleAtFixedRate(this::checkExpiringContracts, 0, 24, TimeUnit.HOURS);
    }

    @Override
    public void onButtonInteraction(@NotNull ButtonInteractionEvent event) {
        String componentId = event.getComponentId();
        if (componentId.startsWith("contract_")) {
            ContractManager contractManager = Main.getContractManager();
            contractManager.handleContractButtonInteraction(event);
        }
    }

    /**
     * Génère un contrat d'entrée pour un nouveau membre.
     * @param member Membre pour lequel générer un contrat d'entrée
     * @return Document contenant les informations du contrat
     */
    public Document generateEntryContract(Member member) {
        Document existingUser = Main.getMongoConnection().getDatabase().getCollection("users").find(new Document("userId", member.getId())).first();
        if (existingUser == null) return null;

        String teamName = existingUser.getString("teamName");
        int salary = MIN_ENTRY_SALARY + new Random().nextInt(MAX_ENTRY_SALARY - MIN_ENTRY_SALARY + 1);
        Date joinDate = Date.from(existingUser.getDate("joinDate").toInstant());

        // Générer un contrat d'entrée standard (3 ans, 2 volets)
        return generateContract(
                member.getId(),          // userId
                teamName,                // teamName
                salary,                  // salary
                ENTRY_CONTRACT_DURATION_DAYS, // years
                "2 volets",              // contractType
                false,                   // hasNTC
                false,                   // hasNMC
                "",
                "",
                joinDate                 // startDate - nouveau paramètre
        );
    }

    /**
     * Surcharge de la méthode generateContract qui utilise la date actuelle comme date de début
     */
    public Document generateContract(String userId, String teamName, int salary, int years, String contractType, boolean hasNTC, boolean hasNMC, String ntcDetails, String nmcDetails) {
        return generateContract(userId, teamName, salary, years, contractType, hasNTC, hasNMC, ntcDetails, nmcDetails, null);
    }

    /**
     * Génère un contrat générique avec les paramètres spécifiés.
     *
     * @param userId ID de l'utilisateur
     * @param teamName Nom de l'équipe
     * @param salary Salaire annuel
     * @param years Durée du contrat en années
     * @param contractType Type de contrat (1 volet/2 volets)
     * @param hasNTC Si le contrat a une clause de non-échange
     * @param hasNMC Si le contrat a une clause de non-mouvement
     * @return Document représentant le contrat
     */
    public Document generateContract(String userId, String teamName, int salary, int years, String contractType, boolean hasNTC, boolean hasNMC, String ntcDetails, String nmcDetails, Date startDate) {
        int adjustedYears = Math.max(1, Math.min(years, MAX_CONTRACT_YEARS));
        int adjustedSalary = Math.max(MIN_SALARY, Math.min(salary, MAX_SALARY));
        Date contractStartDate = startDate != null ? startDate : new Date();

        Calendar calendar = Calendar.getInstance();
        calendar.setTime(contractStartDate);
        int contractDays = adjustedYears * DAYS_PER_YEAR;
        calendar.add(Calendar.DAY_OF_MONTH, contractDays);
        Date expiryDate = calendar.getTime();

        Document contract = new Document()
                .append("_id", new ObjectId())
                .append("userId", userId)
                .append("teamName", teamName)
                .append("salary", adjustedSalary)
                .append("years", adjustedYears)
                .append("startDate", contractStartDate)
                .append("expiryDate", expiryDate)
                .append("type", contractType)
                .append("hasNTC", hasNTC)
                .append("hasNMC", hasNMC)
                .append("ntcDetails", ntcDetails)
                .append("nmcDetails", nmcDetails)
                .append("status", "active");

        updateUserContract(userId, contract);
        updateUserTeam(userId, teamName);
        return contract;
    }

    /**
     * Met à jour les informations de contrat d'un utilisateur.
     * @param userId ID de l'utilisateur
     * @param contract Document du contrat
     */
    private void updateUserContract(String userId, Document contract) {
        Document contractInfo = new Document()
                .append("teamName", contract.getString("teamName"))
                .append("salary", contract.getInteger("salary"))
                .append("years", contract.getInteger("years"))
                .append("startDate", contract.getDate("startDate"))
                .append("expiryDate", contract.getDate("expiryDate"))
                .append("type", contract.getString("type"))
                .append("status", contract.getString("status"));

        if (contract.containsKey("hasNTC")) {
            contractInfo.append("hasNTC", contract.getBoolean("hasNTC"));
        }
        if (contract.containsKey("ntcDetails")) {
            contractInfo.append("ntcDetails", contract.getString("ntcDetails"));
        }
        if (contract.containsKey("hasNMC")) {
            contractInfo.append("hasNMC", contract.getBoolean("hasNMC"));
        }
        if (contract.containsKey("nmcDetails")) {
            contractInfo.append("nmcDetails", contract.getString("nmcDetails"));
        }

        Main.getMongoConnection().getDatabase().getCollection("users").updateOne(
                new Document("userId", userId),
                new Document("$set", new Document("contract", contractInfo))
        );
    }

    /**
     * Vérifie les contrats qui expirent et envoie des notifications.
     */
    public void checkExpiringContracts() {
        Document query = new Document("contract.status", "active").append("contract.expiryDate", new Document("$lte", new Date()));
        for (Document user : Main.getMongoConnection().getDatabase().getCollection("users").find(query)) {
            String userId = user.getString("userId");
            User discordUser = Main.getJda().retrieveUserById(userId).complete();
            if (discordUser != null) {
                sendContractOffers(discordUser);
                Main.getMongoConnection().getDatabase().getCollection("users")
                        .updateOne(
                                new Document("_id", user.getObjectId("_id")),
                                new Document("$set", new Document("contract.status", "expired"))
                        );
            }
        }
    }

    /**
     * Envoie des offres de contrat à un utilisateur.
     * @param user L'utilisateur à qui envoyer les offres
     */
    public void sendContractOffers(User user) {
        try {
            // Obtenir les données de l'utilisateur
            Document userData = Main.getRankManager().getUserData(user.getId());
            if (userData == null) {
                System.err.println("Données utilisateur non trouvées pour l'ID: " + user.getId());
                return;
            }

            // Récupérer l'équipe actuelle
            String currentTeam = userData.getString("teamName");

            // Obtenir deux équipes aléatoires (différentes de l'équipe actuelle)
            List<Document> randomTeams = getRandomTeams(Collections.singletonList(currentTeam));

            // Récupérer l'équipe actuelle depuis la DB
            Document currentTeamData = Main.getTeamManager().getTeamByName(currentTeam);

            // Préparer les 3 offres (équipe actuelle + 2 équipes aléatoires)
            List<ContractOffer> offers = new ArrayList<>();

            // Calculer le salaire en fonction de la réputation du joueur
            Document reputation = (Document) userData.getOrDefault("reputation", new Document("reputationScore", 0));
            int reputationScore = reputation.getInteger("reputationScore", 0);

            // Ajouter l'offre de l'équipe actuelle
            if (currentTeamData != null) {
                offers.add(createOfferForTeam(currentTeamData, reputationScore, true));
            }

            // Ajouter les offres des équipes aléatoires
            for (Document team : randomTeams) {
                offers.add(createOfferForTeam(team, reputationScore, false));
            }

            // Générer l'image des offres
            BufferedImage contractImage = generateContractOffersImage(offers);
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            ImageIO.write(contractImage, "png", outputStream);

            // Créer les boutons pour les offres
            List<Button> buttons = new ArrayList<>();
            for (ContractOffer offer : offers) {
                buttons.add(Button.primary("contract_" + offer.teamName(), "Signer avec " + offer.teamName()));
            }
            buttons.add(Button.danger("contract_decline", "Ne pas signer"));

            // Stocker les offres en cache ou dans la base de données pour référence ultérieure
            storeContractOffers(user.getId(), offers);

            // Envoyer le message privé avec l'image et les boutons
            user.openPrivateChannel().queue(channel -> {
                channel.sendMessage("📬 Vous pouvez signer un nouveau contrat !")
                        .addFiles(FileUpload.fromData(outputStream.toByteArray(), "contract_offers.png"))
                        .addActionRow(buttons.subList(0, Math.min(buttons.size() - 1, 3)).toArray(new Button[0]))
                        .addActionRow(buttons.getLast())
                        .queue();
                channel.sendMessage("⏳ Vous avez 3 jours pour signer un contrat. Passé ce délai, les offres seront retirées " +
                                "et de nouvelles propositions moins avantageuses vous seront faites.")
                        .queue();
            });

            // Programmer une vérification dans 3 jours pour voir si l'utilisateur a signé
            scheduleContractFollowUp(user.getId());

        } catch (Exception e) {
            System.err.println("Erreur lors de l'envoi des offres de contrat à l'utilisateur " + user.getId());
            e.printStackTrace();
        }
    }

    /**
     * Crée une offre de contrat pour une équipe spécifique.
     * @param team Les données de l'équipe
     * @param reputationScore Le score de réputation du joueur
     * @param isCurrentTeam Si c'est l'équipe actuelle du joueur
     * @return Une offre de contrat
     */
    private ContractOffer createOfferForTeam(Document team, int reputationScore, boolean isCurrentTeam) {
        String teamName = team.getString("name");

        // Déterminer la durée du contrat (entre 1 et 8 ans, plus de chance d'avoir un contrat plus long avec une bonne réputation)
        int maxYears = Math.max(1, Math.min(8, (reputationScore / 20) + 1));
        int years = new Random().nextInt(maxYears) + 1;

        // Calculer le salaire basé sur la réputation (min 1M, max 10M)
        // Les joueurs avec une meilleure réputation obtiennent de meilleurs salaires
        double baseSalary = 1.0 + (reputationScore / 10.0);

        // L'équipe actuelle offre généralement un peu plus (loyauté)
        if (isCurrentTeam) baseSalary *= 1.1;

        // Ajout d'une variation aléatoire (±10%)
        double randomFactor = 0.9 + (new Random().nextDouble() * 0.2);
        double salary = baseSalary * randomFactor;

        // S'assurer que le salaire est dans les limites
        salary = Math.max(MIN_SALARY / 1000000.0, Math.min(salary, MAX_SALARY / 1000000.0));

        // Arrondir à 2 décimales
        salary = Math.round(salary * 100.0) / 100.0;

        // Le type de contrat dépend de la réputation
        String contractType;
        if (reputationScore <= TWO_WAY_REPUTATION_THRESHOLD) {
            contractType = (new Random().nextDouble() < 0.7) ? "2 volets" : "1 volet";
        } else {
            contractType = "1 volet";
        }

        // Variables pour suivre les clauses et leurs détails
        boolean hasNTC = false;
        boolean hasNMC = false;
        String ntcDetails = "";
        String nmcDetails = "";

        // Implémenter les clauses en fonction de la réputation
        Random random = new Random();

        // Moins de 20 = aucune clause possible
        // 20 à 40 : clause possible, mais faible
        if (reputationScore >= 20 && reputationScore < 40) {
            // Petite chance d'avoir une NTC limitée
            if (random.nextDouble() < 0.25) {
                hasNTC = true;
                int listSize = random.nextInt(5) + 3; // Liste de 3 à 7 équipes
                boolean isAcceptList = random.nextBoolean();
                ntcDetails = "Liste de " + listSize + " équipes " + (isAcceptList ? "acceptées" : "refusées");
            }
        }
        // 40 à 60 : plus probable, plus d'équipes
        else if (reputationScore < 60) {
            // Chance modérée d'avoir une NTC
            if (random.nextDouble() < 0.4) {
                hasNTC = true;
                int listSize = random.nextInt(10) + 5; // Liste de 5 à 14 équipes
                boolean isAcceptList = random.nextBoolean();
                ntcDetails = "Liste de " + listSize + " équipes " + (isAcceptList ? "acceptées" : "refusées");
            }

            // Très petite chance d'avoir une NMC modifiée
            if (random.nextDouble() < 0.15) {
                hasNMC = true;
                int activeYear = Math.min(years - 1, random.nextInt(3) + 2); // Active après 2-4 ans
                nmcDetails = "Active à partir de la " + activeYear + "e année";
            }
        }
        // 60 à 80 : presque la meilleure
        else if (reputationScore < 80) {
            // Forte chance d'avoir une NTC
            if (random.nextDouble() < 0.65) {
                hasNTC = true;
                double ntcType = random.nextDouble();

                if (ntcType < 0.5) {
                    // NTC complète
                    ntcDetails = "Protection complète contre les échanges";
                } else {
                    // NTC modifiée avec liste plus longue
                    int listSize = random.nextInt(10) + 10; // Liste de 10 à 19 équipes
                    boolean isAcceptList = random.nextBoolean();
                    ntcDetails = "Liste de " + listSize + " équipes " +
                            (isAcceptList ? "acceptées" : "refusées");
                }
            }

            // Chance moyenne d'avoir une NMC
            if (random.nextDouble() < 0.35) {
                hasNMC = true;
                if (random.nextDouble() < 0.6) {
                    int activeYear = Math.min(years - 1, random.nextInt(2) + 1); // Active après 1-2 ans
                    nmcDetails = "Active à partir de la " + activeYear + "e année";
                } else {
                    nmcDetails = "Protection contre le ballottage et la rétrogradation";
                }
            }
        }
        // 80 à 95 : quasiment assurée d'avoir une clause complète
        else if (reputationScore < 95) {
            // Très forte chance d'avoir une NTC complète
            if (random.nextDouble() < 0.9) {
                hasNTC = true;
                ntcDetails = "Protection complète";
            }

            // Bonne chance d'avoir une NMC
            if (random.nextDouble() < 0.5) {
                hasNMC = true;
                nmcDetails = "Protection complète";
            }
        }
        // 95 à 100 : complète, NTC + NMC garantis
        else {
            // NTC et NMC garantis
            hasNTC = true;
            hasNMC = true;
            ntcDetails = "Protection complète";
            nmcDetails = "Protection complète";
        }

        // L'équipe actuelle offre plus facilement des clauses (fidélité)
        if (isCurrentTeam && !hasNTC && reputationScore >= 30 && random.nextDouble() < 0.3) {
            hasNTC = true;
            int listSize = random.nextInt(8) + 5; // Liste de 5 à 12 équipes
            boolean isAcceptList = random.nextBoolean();
            ntcDetails = "Liste de " + listSize + " équipes " + (isAcceptList ? "acceptées" : "refusées");
        }

        return new ContractOffer(teamName, years, salary, contractType, hasNTC, hasNMC, ntcDetails, nmcDetails);
    }

    /**
     * Génère une image d'offres de contrat similaire à celle de l'exemple.
     * @param offers Liste des offres de contrat
     * @return Une image BufferedImage des offres
     */
    private BufferedImage generateContractOffersImage(List<ContractOffer> offers) throws IOException {
        int width = 1200;
        int height = 600; // Hauteur réduite pour éliminer l'espace vide en bas
        int panelWidth = width / offers.size();
        int padding = 15;

        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2d = image.createGraphics();

        // Configuration de l'antialiasing
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        // Fond noir pour l'image globale
        g2d.setColor(new Color(5, 5, 5));
        g2d.fillRect(0, 0, width, height);

        // Dessiner chaque offre
        for (int i = 0; i < offers.size(); i++) {
            ContractOffer offer = offers.get(i);
            int x = i * panelWidth + padding;
            int panelContentWidth = panelWidth - (2 * padding);

            // Bordure du panneau plus foncée
            g2d.setColor(new Color(15, 15, 15));
            g2d.fillRect(x, padding, panelContentWidth, height - (2 * padding));

            // Zone du haut avec la couleur de l'équipe et logo
            Document teamData = Main.getTeamManager().getTeamByName(offer.teamName());
            Color teamColor = Color.GRAY;
            if (teamData != null && teamData.containsKey("color")) {
                String colorHex = teamData.getString("color");
                if (colorHex != null && !colorHex.isEmpty()) {
                    teamColor = Color.decode(colorHex);
                }
            }

            // Ajout du watermark de l'équipe en arrière-plan (logo transparent)
            try {
                if (teamData != null && teamData.containsKey("logo")) {
                    String logoPath = teamData.getString("logo");
                    if (logoPath != null && !logoPath.isEmpty()) {
                        BufferedImage watermarkLogo = ImageUtils.loadImage(logoPath, 300);

                        // Dessiner le watermark avec transparence
                        AlphaComposite alphaChannel = AlphaComposite.getInstance(
                                AlphaComposite.SRC_OVER, 0.08f);
                        Composite originalComposite = g2d.getComposite();
                        g2d.setComposite(alphaChannel);

                        int watermarkY = (height - padding) / 2;
                        g2d.drawImage(watermarkLogo,
                                x + (panelContentWidth - 300) / 2,
                                watermarkY - 100,
                                300, 300, null);

                        g2d.setComposite(originalComposite);
                    }
                }
            } catch (Exception e) {
                System.err.println("Erreur lors du chargement du watermark: " + e.getMessage());
            }

            // Zone rectangulaire en haut pour l'équipe
            g2d.setColor(teamColor);
            g2d.fillRect(x, padding, panelContentWidth, 180);

            // Essayer de charger et afficher le logo de l'équipe
            try {
                if (teamData != null && teamData.containsKey("logo")) {
                    String logoPath = teamData.getString("logo");
                    if (logoPath != null && !logoPath.isEmpty()) {
                        BufferedImage logo = ImageUtils.loadImage(logoPath, 120);
                        g2d.drawImage(logo, x + (panelContentWidth - 120) / 2, padding + 25, 120, 120, null);
                    }
                }
            } catch (Exception e) {
                System.err.println("Erreur lors du chargement du logo de l'équipe: " + e.getMessage());
            }

            // Nom de l'équipe avec plus d'espace après le logo
            g2d.setColor(Color.WHITE);
            g2d.setFont(new Font("Arial", Font.BOLD, 18));
            String teamName = offer.teamName();
            FontMetrics fm = g2d.getFontMetrics();
            int textWidth = fm.stringWidth(teamName);
            g2d.drawString(teamName, x + (panelContentWidth - textWidth) / 2, padding + 180 + 25);

            // Ligne de séparation
            g2d.setColor(new Color(40, 40, 40));
            g2d.fillRect(x + 30, padding + 220, panelContentWidth - 60, 1);

            // Détails du contrat
            int detailsBaseY = padding + 260;
            int rowHeight = 45; // Espacement réduit entre les lignes
            int labelX = x + 30;
            int valueX = x + panelContentWidth - 30;

            // Years
            g2d.setColor(new Color(180, 180, 180));
            g2d.setFont(new Font("Arial", Font.PLAIN, 18));
            g2d.drawString("Années:", labelX, detailsBaseY);
            g2d.setColor(Color.WHITE);
            g2d.setFont(new Font("Arial", Font.BOLD, 20));
            String yearsText = String.valueOf(offer.years());
            fm = g2d.getFontMetrics();
            textWidth = fm.stringWidth(yearsText);
            g2d.drawString(yearsText, valueX - textWidth, detailsBaseY);

            // Yearly salary
            g2d.setColor(new Color(180, 180, 180));
            g2d.setFont(new Font("Arial", Font.PLAIN, 18));
            g2d.drawString("Salaire annuel:", labelX, detailsBaseY + rowHeight);
            g2d.setColor(new Color(100, 255, 100)); // Vert pour l'argent
            g2d.setFont(new Font("Arial", Font.BOLD, 20));
            String salaryText = "$" + String.format("%.2fM", offer.salary());
            fm = g2d.getFontMetrics();
            textWidth = fm.stringWidth(salaryText);
            g2d.drawString(salaryText, valueX - textWidth, detailsBaseY + rowHeight);

            // Contract type
            g2d.setColor(new Color(180, 180, 180));
            g2d.setFont(new Font("Arial", Font.PLAIN, 18));
            g2d.drawString("Type de contrat:", labelX, detailsBaseY + 2 * rowHeight);
            g2d.setColor(Color.WHITE);
            g2d.setFont(new Font("Arial", Font.BOLD, 20));
            String contractTypeText = offer.contractType();
            fm = g2d.getFontMetrics();
            textWidth = fm.stringWidth(contractTypeText);
            g2d.drawString(contractTypeText, valueX - textWidth, detailsBaseY + 2 * rowHeight);

            // Clauses spéciales - Nouveau format comme demandé
            g2d.setColor(new Color(180, 180, 180));
            g2d.setFont(new Font("Arial", Font.PLAIN, 18));
            g2d.drawString("Clauses:", labelX, detailsBaseY + 3 * rowHeight);

            int clauseBaseY = detailsBaseY + 3 * rowHeight + 30; // Marge après "Clauses:"
            int clauseIndent = 20; // Indentation pour les éléments de la liste
            int clauseSpacing = 25; // Espace entre les clauses

            if (!offer.hasNTC() && !offer.hasNMC()) {
                g2d.setColor(Color.WHITE);
                g2d.setFont(new Font("Arial", Font.BOLD, 16));
                g2d.drawString("Aucune", labelX + clauseIndent, clauseBaseY);
            } else {
                int currentY = clauseBaseY;

                if (offer.hasNTC()) {
                    // Format "- NTC : détails"
                    g2d.setColor(Color.WHITE);
                    g2d.setFont(new Font("Arial", Font.PLAIN, 16));
                    g2d.drawString("-", labelX, currentY);

                    g2d.setColor(new Color(255, 180, 50)); // Orange pour NTC
                    g2d.setFont(new Font("Arial", Font.BOLD, 16));
                    g2d.drawString("NTC", labelX + clauseIndent, currentY);

                    // Détails NTC à la suite
                    g2d.setColor(new Color(200, 200, 200));
                    g2d.setFont(new Font("Arial", Font.ITALIC, 14));
                    g2d.drawString(": " + offer.ntcDetails(), labelX + clauseIndent + 45, currentY);

                    currentY += clauseSpacing;
                }

                if (offer.hasNMC()) {
                    // Format "- NMC : détails"
                    g2d.setColor(Color.WHITE);
                    g2d.setFont(new Font("Arial", Font.PLAIN, 16));
                    g2d.drawString("-", labelX, currentY);

                    g2d.setColor(new Color(50, 180, 255)); // Bleu pour NMC
                    g2d.setFont(new Font("Arial", Font.BOLD, 16));
                    g2d.drawString("NMC", labelX + clauseIndent, currentY);

                    // Détails NMC à la suite
                    g2d.setColor(new Color(200, 200, 200));
                    g2d.setFont(new Font("Arial", Font.ITALIC, 14));
                    g2d.drawString(": " + offer.nmcDetails(), labelX + clauseIndent + 45, currentY);
                }
            }
        }

        // Bordure externe
        g2d.setColor(new Color(40, 40, 40));
        g2d.setStroke(new BasicStroke(2.0f));
        g2d.drawRect(0, 0, width - 1, height - 1);

        g2d.dispose();
        return image;
    }

    /**
     * Stocke les offres de contrat pour une référence ultérieure.
     * @param userId ID de l'utilisateur
     * @param offers Liste des offres
     */
    private void storeContractOffers(String userId, List<ContractOffer> offers) {
        // Convertir les offres en documents
        List<Document> offerDocs = offers.stream()
                .map(offer -> new Document()
                        .append("teamName", offer.teamName())
                        .append("years", offer.years())
                        .append("salary", offer.salary())
                        .append("contractType", offer.contractType())
                        .append("hasNTC", offer.hasNTC())
                        .append("hasNMC", offer.hasNMC())
                        .append("ntcDetails", offer.ntcDetails())
                        .append("nmcDetails", offer.nmcDetails()))
                .collect(Collectors.toList());

        // Date d'expiration (3 jours)
        Calendar calendar = Calendar.getInstance();
        calendar.add(Calendar.DAY_OF_MONTH, CONTRACT_RESPONSE_DEADLINE_DAYS);

        // Document des offres actives
        Document offersDoc = new Document()
                .append("userId", userId)
                .append("offers", offerDocs)
                .append("createdAt", new Date())
                .append("expiresAt", calendar.getTime())
                .append("status", "pending");

        // Supprimer les anciennes offres en attente pour cet utilisateur
        Main.getMongoConnection().getDatabase().getCollection("contractOffers").deleteMany(
                new Document("userId", userId).append("status", "pending"));

        // Insérer les nouvelles offres
        Main.getMongoConnection().getDatabase().getCollection("contractOffers").insertOne(offersDoc);
    }

    /**
     * Programme une vérification ultérieure pour voir si l'utilisateur a signé un contrat.
     * @param userId ID de l'utilisateur
     */
    private void scheduleContractFollowUp(String userId) {
        scheduler.schedule(() -> {
            // Vérifier si l'utilisateur a signé un contrat
            Document query = new Document("userId", userId)
                    .append("status", "pending");

            Document pendingOffers = Main.getMongoConnection().getDatabase().getCollection("contractOffers").find(query).first();

            if (pendingOffers != null) {
                // L'utilisateur n'a pas signé, devenir agent libre
                makeUserFreeAgent(userId);

                // Marquer les offres comme expirées
                Main.getMongoConnection().getDatabase().getCollection("contractOffers").updateOne(
                        new Document("_id", pendingOffers.getObjectId("_id")),
                        new Document("$set", new Document("status", "expired")));

                // Notifier l'utilisateur
                User user = Main.getJda().retrieveUserById(userId).complete();
                if (user != null) {
                    user.openPrivateChannel().queue(channel -> {
                        channel.sendMessage("⚠️ Vos offres de contrat ont expiré. Vous êtes maintenant un agent libre. " +
                                        "Vous pouvez demander un nouveau contrat à tout moment via la commande `/contrat`.")
                                .queue();
                    });
                }
            }
        }, CONTRACT_RESPONSE_DEADLINE_DAYS, TimeUnit.DAYS);
    }

    /**
     * Transforme un utilisateur en agent libre.
     * @param userId ID de l'utilisateur
     */
    public void makeUserFreeAgent(String userId) {
        Document userData = Main.getRankManager().getUserData(userId);
        if (userData != null) {
            Main.getMongoConnection().getDatabase().getCollection("users").updateOne(
                    new Document("_id", userData.getObjectId("_id")),
                    new Document("$set", new Document("teamName", "Agent Libre").append("contract.status", "unsigned"))
            );
        }
    }

    /**
     * Traite l'interaction avec un bouton de contrat.
     * @param event L'événement d'interaction avec le bouton
     */
    public void handleContractButtonInteraction(ButtonInteractionEvent event) {
        String[] buttonData = event.getComponentId().split("_", 2);
        if (buttonData.length < 2) return;
        String action = buttonData[0];
        String value = buttonData[1];
        if (action.equals("contract")) {
            if (value.equals("decline")) {
                makeUserFreeAgent(event.getUser().getId());
                event.reply("Vous avez refusé toutes les offres. Vous êtes maintenant un agent libre.").queue();
            } else {
                signContractWithTeam(event.getUser().getId(), value, event);
            }
        }
    }

    /**
     * Signe un contrat avec une équipe spécifique.
     * @param userId ID de l'utilisateur
     * @param teamName Nom de l'équipe
     * @param event Événement d'interaction pour répondre
     */
    private void signContractWithTeam(String userId, String teamName, ButtonInteractionEvent event) {
        Document offersDoc = Main.getMongoConnection().getDatabase().getCollection("contractOffers")
                .find(new Document("userId", userId).append("status", "pending"))
                .first();
        if (offersDoc == null) {
            event.reply("Aucune offre de contrat en attente n'a été trouvée.").setEphemeral(true).queue();
            return;
        }
        List<Document> offers = offersDoc.getList("offers", Document.class);
        Document selectedOffer = null;
        for (Document offer : offers) {
            if (teamName.equals(offer.getString("teamName"))) {
                selectedOffer = offer;
                break;
            }
        }
        if (selectedOffer == null) {
            event.reply("Cette offre n'est plus disponible.").setEphemeral(true).queue();
            return;
        }

        // Créer le nouveau contrat
        int years = selectedOffer.getInteger("years");
        double salaryInMillions = selectedOffer.getDouble("salary");
        String contractType = selectedOffer.getString("contractType");
        boolean hasNTC = selectedOffer.getBoolean("hasNTC", false);
        boolean hasNMC = selectedOffer.getBoolean("hasNMC", false);
        String ntcDetails = selectedOffer.getString("ntcDetails") != null ? selectedOffer.getString("ntcDetails") : "";
        String nmcDetails = selectedOffer.getString("nmcDetails") != null ? selectedOffer.getString("nmcDetails") : "";

        // Créer le contrat avec la fonction générique
        Document contract = generateContract(
                userId,
                teamName,
                (int)(salaryInMillions * 1000000), // Convertir en dollars
                years,
                contractType,
                hasNTC,
                hasNMC,
                ntcDetails,
                nmcDetails
        );

        // Marquer les offres comme acceptées
        Main.getMongoConnection().getDatabase().getCollection("contractOffers").updateOne(
                new Document("_id", offersDoc.getObjectId("_id")),
                new Document("$set", new Document("status", "accepted")
                        .append("acceptedTeam", teamName)));

        // Répondre à l'utilisateur
        SimpleDateFormat dateFormat = new SimpleDateFormat("dd/MM/yyyy", Locale.FRENCH);

        StringBuilder clausesText = new StringBuilder();
        if (hasNTC || hasNMC) {
            clausesText.append(" avec les clauses suivantes:\n");
            if (hasNTC && hasNMC) {
                clausesText.append("- NTC + NMC\n");
                clausesText.append("  • NTC : ").append(ntcDetails).append("\n");
                clausesText.append("  • NMC : ").append(nmcDetails);
            } else if (hasNTC) {
                clausesText.append("- NTC : ").append(ntcDetails);
            } else {
                clausesText.append("- NMC : ").append(nmcDetails);
            }
        }

        event.reply("🎉 Félicitations ! Vous avez signé un contrat de " + years + " ans avec " + teamName +
                " pour un salaire annuel de $" + String.format("%.2fM", salaryInMillions) + clausesText + "." +
                "\nLe contrat expire le " + dateFormat.format(contract.getDate("expiryDate")) + ".").queue();
    }

    /**
     * Met à jour l'équipe d'un utilisateur dans la base de données.
     * @param userId ID de l'utilisateur
     * @param teamName Nom de l'équipe
     */
    private void updateUserTeam(String userId, String teamName) {
        Document userData = Main.getRankManager().getUserData(userId);
        if (userData != null) {
            Main.getMongoConnection().getDatabase().getCollection("users").updateOne(
                    new Document("_id", userData.getObjectId("_id")),
                    new Document("$set", new Document("teamName", teamName)));
        }
    }

    /**
     * Obtient plusieurs équipes aléatoires, en excluant certaines.
     *
     * @param exclude Liste des noms d'équipes à exclure
     * @return Liste de documents d'équipes
     */
    private List<Document> getRandomTeams(List<String> exclude) {
        List<Document> allTeams = new ArrayList<>();
        Main.getMongoConnection().getDatabase().getCollection("teams").find().into(allTeams);
        List<Document> availableTeams = allTeams.stream().filter(team -> !exclude.contains(team.getString("name"))).collect(Collectors.toList());
        if (availableTeams.size() <= 2) return availableTeams;
        List<Document> selectedTeams = new ArrayList<>();
        Random random = new Random();
        while (selectedTeams.size() < 2 && !availableTeams.isEmpty()) {
            int index = random.nextInt(availableTeams.size());
            selectedTeams.add(availableTeams.remove(index));
        }
        return selectedTeams;
    }

    /**
     * Classe interne représentant une offre de contrat.
     * @param teamName Nom de l'équipe
     * @param years Durée du contrat en années
     * @param salary Salaire annuel en millions
     * @param contractType Type de contrat (1 volet/2 volets)
     * @param hasNTC Contrat avec clause de non-échange
     * @param hasNMC Contrat avec clause de non-mouvement
     * @param ntcDetails Détails de la clause de non-échange
     * @param nmcDetails Détails de la clause de non-mouvement
     */
    public record ContractOffer(String teamName, int years, double salary, String contractType, boolean hasNTC, boolean hasNMC, String ntcDetails, String nmcDetails) {}
}