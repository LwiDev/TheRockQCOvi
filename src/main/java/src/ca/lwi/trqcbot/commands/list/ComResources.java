package src.ca.lwi.trqcbot.commands.list;

import com.mongodb.client.MongoCollection;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.DefaultMemberPermissions;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData;
import net.dv8tion.jda.api.interactions.components.ActionRow;
import net.dv8tion.jda.api.interactions.components.buttons.Button;
import net.dv8tion.jda.api.interactions.components.selections.StringSelectMenu;
import net.dv8tion.jda.api.interactions.components.text.TextInput;
import net.dv8tion.jda.api.interactions.components.text.TextInputStyle;
import net.dv8tion.jda.api.interactions.modals.Modal;
import org.bson.Document;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import src.ca.lwi.trqcbot.Main;
import src.ca.lwi.trqcbot.commands.Command;
import src.ca.lwi.trqcbot.handlers.ResourcesEmbedHandler;

import java.util.*;
import java.util.concurrent.CompletableFuture;

public class ComResources extends Command {

    private final Logger LOGGER = LoggerFactory.getLogger(ComResources.class);
    private final String COLLECTION_NAME = "resources";

    // Section keys for MongoDB
    private final String SECTION_ROLES = "roles";
    private final String SECTION_CATEGORIES = "categories";
    private final String SECTION_CHANNELS = "importantChannels";
    private final String SECTION_LINKS = "usefulLinks";

    // Component IDs
    private final String SELECT_SECTION = "resources_section_select";
    private final String BTN_ADD = "resources_add_item";
    private final String BTN_BACK_TO_SECTIONS = "resources_back_to_sections";
    private final String BTN_EDIT = "resources_edit_item";
    private final String BTN_DELETE = "resources_delete_item";
    private final String BTN_SERVER_NAME = "resources_server_name";

    // Modal IDs
    private final String MODAL_ADD = "resources_add_modal";
    private final String MODAL_EDIT = "resources_edit_modal";
    private final String MODAL_DELETE = "resources_delete_modal";
    private final String MODAL_SERVER_NAME = "resources_server_name_modal";

    // Cache section choice for each user
    private final Map<String, String> userSectionChoices = new HashMap<>();

    private boolean listenersRegistered = false;

    public ComResources() {
        super("resources", "Gérer le message du salon ressources");

        // Restricted to admins
        setDefaultPermissions(DefaultMemberPermissions.DISABLED);

        // Create simplified subcommands
        SubcommandData updateCmd = new SubcommandData("update", "Mettre à jour le message d'accueil");
        updateCmd.addOption(OptionType.BOOLEAN, "force_new", "Créer un nouveau message même si un existe déjà", false);

        SubcommandData manageCmd = new SubcommandData("manage", "Gérer les sections et les éléments du message");

        SubcommandData viewConfigCmd = new SubcommandData("view", "Afficher la configuration actuelle");

        addSubcommands(updateCmd, manageCmd, viewConfigCmd);
    }

    public void registerEventListeners(net.dv8tion.jda.api.JDA jda) {
        if (!this.listenersRegistered) {
                jda.addEventListener(
                        new ResourcesButtonListener(),
                        new ResourcesSelectMenuListener(),
                        new ResourcesModalListener()
                );
                this.listenersRegistered = true;
        }
    }

    @Override
    public void onSlash(SlashCommandInteractionEvent e) {
        if (!e.isFromGuild()) {
            e.reply("Cette commande ne peut être utilisée que sur un serveur.").setEphemeral(true).queue();
            return;
        }

        if (!Objects.requireNonNull(e.getMember()).hasPermission(Permission.ADMINISTRATOR)) {
            e.reply("Vous n'avez pas les permissions nécessaires pour utiliser cette commande.").setEphemeral(true).queue();
            return;
        }

        String subcommandName = e.getSubcommandName();
        if (subcommandName == null) {
            e.reply("Veuillez spécifier une sous-commande.").setEphemeral(true).queue();
            return;
        }

        try {
            switch (subcommandName.toLowerCase()) {
                case "update":
                    e.deferReply(true).queue();
                    handleUpdate(e);
                    break;
                case "manage":
                    handleManage(e);
                    break;
                case "view":
                    e.deferReply(true).queue();
                    handleViewConfig(e);
                    break;
                default:
                    e.reply("Sous-commande inconnue.").setEphemeral(true).queue();
            }
        } catch (Exception ex) {
            LOGGER.error("Erreur lors de l'exécution de la commande {}: {}", subcommandName, ex.getMessage());
            e.reply("Une erreur est survenue : " + ex.getMessage()).setEphemeral(true).queue();
        }
    }

    private void handleUpdate(SlashCommandInteractionEvent e) {
        boolean forceNew = e.getOption("force_new") != null && Objects.requireNonNull(e.getOption("force_new")).getAsBoolean();

        ResourcesEmbedHandler.updateWelcomeEmbed(forceNew)
                .thenAccept(v -> e.getHook().sendMessage("✅ Le message d'accueil a été mis à jour.").queue(message -> {
                    message.delete().queueAfter(3, java.util.concurrent.TimeUnit.SECONDS);
                }))
                .exceptionally(error -> {
                    LOGGER.error("Erreur lors de la mise à jour du message : {}", error.getMessage(), error);
                    e.getHook().sendMessage("❌ Erreur lors de la mise à jour : " + error.getMessage()).queue();
                    return null;
                });
    }

    private void handleManage(SlashCommandInteractionEvent e) {
        // Create section selector
        StringSelectMenu sectionSelect = StringSelectMenu.create(SELECT_SECTION)
                .setPlaceholder("Choisir la section à modifier")
                .addOption("🎭 Rôles", SECTION_ROLES, "Gérer les rôles affichés dans l'embed")
                .addOption("📂 Catégories", SECTION_CATEGORIES, "Gérer les catégories affichées dans l'embed")
                .addOption("📌 Salons importants", SECTION_CHANNELS, "Gérer les salons importants affichés dans l'embed")
                .addOption("🔗 Liens utiles", SECTION_LINKS, "Gérer les liens utiles affichés dans l'embed")
                .build();

        // Create buttons for actions
        Button addButton = Button.primary(BTN_ADD, "Ajouter").asDisabled();
        Button editButton = Button.secondary(BTN_EDIT, "Modifier").asDisabled();
        Button deleteButton = Button.danger(BTN_DELETE, "Supprimer").asDisabled();
        Button serverNameButton = Button.success(BTN_SERVER_NAME, "Modifier le nom du serveur");

        e.reply("👋 **Interface de gestion des ressources**\n" +
                        "Sélectionnez d'abord une section, puis choisissez l'action à effectuer.")
                .addComponents(
                        ActionRow.of(sectionSelect),
                        ActionRow.of(addButton, editButton, deleteButton),
                        ActionRow.of(serverNameButton)
                )
                .setEphemeral(true)
                .queue();
    }

    private void handleViewConfig(SlashCommandInteractionEvent e) {
        MongoCollection<Document> collection = Main.getMongoConnection().getDatabase().getCollection(COLLECTION_NAME);
        Document configDoc = collection.find(new Document("type", "config")).first();
        if (configDoc == null) {
            e.getHook().sendMessage("❌ Configuration non trouvée. Veuillez d'abord exécuter /resources update.").queue();
            return;
        }

        String serverName = configDoc.getString("serverName");
        List<Document> roles = configDoc.getList(SECTION_ROLES, Document.class, new ArrayList<>());
        List<Document> categories = configDoc.getList(SECTION_CATEGORIES, Document.class, new ArrayList<>());
        List<Document> channels = configDoc.getList(SECTION_CHANNELS, Document.class, new ArrayList<>());
        List<Document> links = configDoc.getList(SECTION_LINKS, Document.class, new ArrayList<>());

        StringBuilder details = new StringBuilder();
        details.append("📊 **Configuration actuelle du message d'accueil**\n")
                .append("• Nom du serveur: ").append(serverName).append("\n\n");

        // Liste des rôles
        details.append("**Rôles** (").append(roles.size()).append("):\n");
        for (Document role : roles) {
            details.append("- ").append(role.getString("emoji")).append(" **")
                    .append(role.getString("name")).append("**: ")
                    .append(role.getString("description")).append("\n");
        }
        details.append("\n");

        // Liste des catégories
        details.append("**Catégories** (").append(categories.size()).append("):\n");
        for (Document cat : categories) {
            details.append("- ").append(cat.getString("emoji")).append(" **")
                    .append(cat.getString("name")).append("**: ")
                    .append(cat.getString("description")).append("\n");
        }
        details.append("\n");

        // Liste des salons
        details.append("**Salons importants** (").append(channels.size()).append("):\n");
        for (Document chan : channels) {
            details.append("- ").append(chan.getString("emoji")).append(" **")
                    .append(chan.getString("name")).append("**: ")
                    .append(chan.getString("description")).append("\n");
        }
        details.append("\n");

        // Liste des liens
        details.append("**Liens utiles** (").append(links.size()).append("):\n");
        for (Document link : links) {
            details.append("- ").append(link.getString("emoji")).append(" **")
                    .append(link.getString("name")).append("**: ")
                    .append(link.getString("url")).append("\n");
        }

        e.getHook().sendMessage(details.toString()).queue();
    }

    /**
     * Button listener for resource management
     */
    private class ResourcesButtonListener implements net.dv8tion.jda.api.hooks.EventListener {
        @Override
        public void onEvent(net.dv8tion.jda.api.events.GenericEvent event) {
            if (event instanceof ButtonInteractionEvent) {
                ButtonInteractionEvent e = (ButtonInteractionEvent) event;
                String buttonId = e.getComponentId();
                String userId = e.getUser().getId();
                if (buttonId.startsWith("confirm_delete:")) {
                    String[] parts = buttonId.split(":");
                    String sectionKey = parts[1];
                    String itemName = parts[2];
                    handleConfirmDelete(e, sectionKey, itemName);
                } else if (buttonId.startsWith("cancel_delete:")) {
                    e.getHook().sendMessage("Suppression annulée.")
                            .setEphemeral(true)
                            .queue(message -> {
                                message.delete().queueAfter(3, java.util.concurrent.TimeUnit.SECONDS);
                            });
                } else {
                    switch (buttonId) {
                        case BTN_ADD:
                            handleAddButtonClick(e, userId);
                            break;
                        case BTN_EDIT:
                            handleEditButtonClick(e, userId);
                            break;
                        case BTN_DELETE:
                            handleDeleteButtonClick(e, userId);
                            break;
                        case BTN_SERVER_NAME:
                            handleServerNameButtonClick(e);
                            break;
                        case BTN_BACK_TO_SECTIONS:
                            handleBackToSectionsButton(e);
                            break;
                    }
                }
            }
        }

        private void handleAddButtonClick(ButtonInteractionEvent e, String userId) {
            String sectionKey = userSectionChoices.getOrDefault(userId, "");
            if (sectionKey.isEmpty()) {
                e.reply("Veuillez d'abord sélectionner une section.").setEphemeral(true).queue();
                return;
            }

            String sectionName = getSectionDisplayName(sectionKey);
            Modal.Builder modalBuilder = Modal.create(MODAL_ADD + ":" + sectionKey, "Ajouter un " + sectionName);
            TextInput emojiInput = TextInput.create("emoji", "Emoji", TextInputStyle.SHORT).setPlaceholder("Cliquez ici puis utilisez Ctrl+Cmd+Space ou Win+. pour les emojis").setRequired(true).setMaxLength(5).build();
            TextInput nameInput = TextInput.create("name", "Nom", TextInputStyle.SHORT).setPlaceholder("Nom affiché dans le message").setRequired(true).setMaxLength(50).build();
            TextInput thirdInput;
            if (sectionKey.equals(SECTION_LINKS)) {
                thirdInput = TextInput.create("url", "URL", TextInputStyle.SHORT).setPlaceholder("Exemple: https://discord.gg/...").setRequired(true).build();
            } else {
                thirdInput = TextInput.create("description", "Description", TextInputStyle.PARAGRAPH).setPlaceholder("Description détaillée").setRequired(true).setMaxLength(200).build();
            }
            modalBuilder.addComponents(ActionRow.of(emojiInput), ActionRow.of(nameInput), ActionRow.of(thirdInput));
            e.replyModal(modalBuilder.build()).queue();
        }

        private void handleEditButtonClick(ButtonInteractionEvent e, String userId) {
            String sectionKey = userSectionChoices.getOrDefault(userId, "");
            if (sectionKey.isEmpty()) {
                e.reply("Veuillez d'abord sélectionner une section.").setEphemeral(true).queue();
                return;
            }

            // Get items from the selected section
            MongoCollection<Document> collection = Main.getMongoConnection().getDatabase().getCollection(COLLECTION_NAME);
            Document configDoc = collection.find(new Document("type", "config")).first();
            if (configDoc == null) {
                e.reply("❌ Configuration non trouvée. Veuillez d'abord exécuter /resources update.").setEphemeral(true).queue();
                return;
            }

            List<Document> items = configDoc.getList(sectionKey, Document.class, new ArrayList<>());
            if (items.isEmpty()) {
                e.reply("Aucun élément trouvé dans cette section.").setEphemeral(true).queue();
                return;
            }

            // Create a select menu with all items
            StringSelectMenu.Builder menuBuilder = StringSelectMenu.create("edit_item_select:" + sectionKey)
                    .setPlaceholder("Choisir l'élément à modifier");

            for (Document item : items) {
                String name = item.getString("name");
                String emoji = item.getString("emoji");
                menuBuilder.addOption(emoji + " " + name, name);
            }

            e.reply("Choisissez l'élément à modifier :").addComponents(ActionRow.of(menuBuilder.build()))
                    .setEphemeral(true).queue();
        }

        private void handleDeleteButtonClick(ButtonInteractionEvent e, String userId) {
            String sectionKey = userSectionChoices.getOrDefault(userId, "");
            if (sectionKey.isEmpty()) {
                e.reply("Veuillez d'abord sélectionner une section.").setEphemeral(true).queue();
                return;
            }

            // Get items from the selected section
            MongoCollection<Document> collection = Main.getMongoConnection().getDatabase().getCollection(COLLECTION_NAME);
            Document configDoc = collection.find(new Document("type", "config")).first();
            if (configDoc == null) {
                e.reply("❌ Configuration non trouvée. Veuillez d'abord exécuter /resources update.").setEphemeral(true).queue();
                return;
            }

            List<Document> items = configDoc.getList(sectionKey, Document.class, new ArrayList<>());
            if (items.isEmpty()) {
                e.reply("Aucun élément trouvé dans cette section.").setEphemeral(true).queue();
                return;
            }

            // Create a select menu with all items
            StringSelectMenu.Builder menuBuilder = StringSelectMenu.create("delete_item_select:" + sectionKey)
                    .setPlaceholder("Choisir l'élément à supprimer");

            for (Document item : items) {
                String name = item.getString("name");
                String emoji = item.getString("emoji");
                menuBuilder.addOption(emoji + " " + name, name);
            }

            e.reply("⚠️ Choisissez l'élément à supprimer :").addComponents(ActionRow.of(menuBuilder.build()))
                    .setEphemeral(true).queue();
        }

        private void handleConfirmDelete(ButtonInteractionEvent e, String sectionKey, String itemName) {
            e.deferReply(true).queue();
            removeItemFromSection(sectionKey, itemName)
                    .thenAccept(v -> e.getHook().sendMessage("✅ L'élément **" + itemName + "** a été supprimé avec succès.").queue(message -> {
                        message.delete().queueAfter(3, java.util.concurrent.TimeUnit.SECONDS);
                    }))
                    .exceptionally(error -> {
                        e.getHook().sendMessage("❌ Erreur lors de la suppression : " + error.getMessage()).queue();
                        return null;
                    });
        }

        private void handleServerNameButtonClick(ButtonInteractionEvent e) {
            TextInput nameInput = TextInput.create("server_name", "Nom du serveur", TextInputStyle.SHORT)
                    .setPlaceholder("Exemple: TheRockQC")
                    .setRequired(true)
                    .setMaxLength(50)
                    .build();

            Modal modal = Modal.create(MODAL_SERVER_NAME, "Modifier le nom du serveur")
                    .addComponents(ActionRow.of(nameInput))
                    .build();

            e.replyModal(modal).queue();
        }

        private void handleBackToSectionsButton(ButtonInteractionEvent e) {
            // Réinitialiser le choix de section de l'utilisateur
            String userId = e.getUser().getId();
            userSectionChoices.remove(userId);

            // Recréer le menu de sélection de section
            StringSelectMenu sectionSelect = StringSelectMenu.create(SELECT_SECTION)
                    .setPlaceholder("Choisir la section à modifier")
                    .addOption("🎭 Rôles", SECTION_ROLES, "Gérer les rôles affichés dans l'embed")
                    .addOption("📂 Catégories", SECTION_CATEGORIES, "Gérer les catégories affichées dans l'embed")
                    .addOption("📌 Salons importants", SECTION_CHANNELS, "Gérer les salons importants affichés dans l'embed")
                    .addOption("🔗 Liens utiles", SECTION_LINKS, "Gérer les liens utiles affichés dans l'embed")
                    .build();

            // Désactiver les boutons d'action
            Button addButton = Button.primary(BTN_ADD, "Ajouter").asDisabled();
            Button editButton = Button.secondary(BTN_EDIT, "Modifier").asDisabled();
            Button deleteButton = Button.danger(BTN_DELETE, "Supprimer").asDisabled();
            Button serverNameButton = Button.success(BTN_SERVER_NAME, "Modifier le nom du serveur");

            e.editComponents(
                            ActionRow.of(sectionSelect),
                            ActionRow.of(addButton, editButton, deleteButton),
                            ActionRow.of(serverNameButton)
                    ).setContent("👋 **Interface de gestion des ressources**\n" +
                            "Sélectionnez d'abord une section, puis choisissez l'action à effectuer.")
                    .queue();
        }
    }

    /**
     * Select menu listener for resource management
     */
    private class ResourcesSelectMenuListener implements net.dv8tion.jda.api.hooks.EventListener {
        @Override
        public void onEvent(net.dv8tion.jda.api.events.GenericEvent event) {
            if (event instanceof StringSelectInteractionEvent) {
                StringSelectInteractionEvent e = (StringSelectInteractionEvent) event;
                String menuId = e.getComponentId();

                if (menuId.equals(SELECT_SECTION)) {
                    handleSectionSelect(e);
                } else if (menuId.startsWith("edit_item_select:")) {
                    handleEditItemSelect(e);
                } else if (menuId.startsWith("delete_item_select:")) {
                    handleDeleteItemSelect(e);
                }
            }
        }

        private void handleSectionSelect(StringSelectInteractionEvent e) {
            String selectedSection = e.getValues().get(0);
            String userId = e.getUser().getId();

            // Store the user's selection
            userSectionChoices.put(userId, selectedSection);

            // Enable action buttons
            Button addButton = Button.primary(BTN_ADD, "Ajouter");
            Button editButton = Button.secondary(BTN_EDIT, "Modifier");
            Button deleteButton = Button.danger(BTN_DELETE, "Supprimer");
            Button backButton = Button.secondary("resources_back_to_sections", "← Retour aux sections");
            Button serverNameButton = Button.success(BTN_SERVER_NAME, "Modifier le nom du serveur");

            e.editComponents(
                            ActionRow.of(addButton, editButton, deleteButton),
                            ActionRow.of(backButton),
                            ActionRow.of(serverNameButton)
                    ).setContent("👋 **Interface de gestion des ressources**\n" +
                            "Section sélectionnée: **" + getSectionDisplayName(selectedSection) + "**\n" +
                            "Choisissez maintenant l'action à effectuer.")
                    .queue();
        }

        private void handleEditItemSelect(StringSelectInteractionEvent e) {
            String[] parts = e.getComponentId().split(":");
            String sectionKey = parts[1];
            String itemName = e.getValues().get(0);

            // Find the item
            MongoCollection<Document> collection = Main.getMongoConnection().getDatabase().getCollection(COLLECTION_NAME);
            Document configDoc = collection.find(new Document("type", "config")).first();
            List<Document> items = configDoc.getList(sectionKey, Document.class, new ArrayList<>());

            Document itemToEdit = null;
            for (Document item : items) {
                if (item.getString("name").equals(itemName)) {
                    itemToEdit = item;
                    break;
                }
            }

            if (itemToEdit == null) {
                e.reply("❌ Élément non trouvé.").setEphemeral(true).queue();
                return;
            }

            // Build edit modal
            Modal.Builder modalBuilder = Modal.create(MODAL_EDIT + ":" + sectionKey + ":" + itemName,
                    "Modifier " + getSectionItemName(sectionKey) + ": " + itemName);

            // Common fields
            TextInput emojiInput = TextInput.create("emoji", "Emoji", TextInputStyle.SHORT)
                    .setValue(itemToEdit.getString("emoji"))
                    .setRequired(true)
                    .setMaxLength(5)
                    .build();

            TextInput nameInput = TextInput.create("name", "Nom", TextInputStyle.SHORT)
                    .setValue(itemToEdit.getString("name"))
                    .setRequired(true)
                    .setMaxLength(50)
                    .build();

            // Different third field based on section
            TextInput thirdInput;
            if (sectionKey.equals(SECTION_LINKS)) {
                thirdInput = TextInput.create("url", "URL", TextInputStyle.SHORT)
                        .setValue(itemToEdit.getString("url"))
                        .setRequired(true)
                        .build();
            } else {
                thirdInput = TextInput.create("description", "Description", TextInputStyle.PARAGRAPH)
                        .setValue(itemToEdit.getString("description"))
                        .setRequired(true)
                        .setMaxLength(200)
                        .build();
            }

            modalBuilder.addComponents(
                    ActionRow.of(emojiInput),
                    ActionRow.of(nameInput),
                    ActionRow.of(thirdInput)
            );

            e.replyModal(modalBuilder.build()).queue();
        }

        private void handleDeleteItemSelect(StringSelectInteractionEvent e) {
            String[] parts = e.getComponentId().split(":");
            String sectionKey = parts[1];
            String itemName = e.getValues().get(0);
            Button confirmButton = Button.danger("confirm_delete:" + sectionKey + ":" + itemName, "Confirmer");
            Button cancelButton = Button.secondary("cancel_delete:" + sectionKey + ":" + itemName, "Annuler");
            e.reply("⚠️ Êtes-vous sûr de vouloir supprimer **" + itemName + "** ?")
                    .addComponents(ActionRow.of(confirmButton, cancelButton))
                    .setEphemeral(true)
                    .queue();
        }
    }

    /**
     * Modal listener for resource management
     */
    private class ResourcesModalListener implements net.dv8tion.jda.api.hooks.EventListener {
        @Override
        public void onEvent(@NotNull net.dv8tion.jda.api.events.GenericEvent event) {
            if (event instanceof ModalInteractionEvent) {
                ModalInteractionEvent e = (ModalInteractionEvent) event;
                String modalId = e.getModalId();
                e.deferReply(true).queue();
                try {
                    if (modalId.startsWith(MODAL_ADD)) {
                        handleAddModal(e);
                    } else if (modalId.startsWith(MODAL_EDIT)) {
                        handleEditModal(e);
                    } else if (modalId.startsWith(MODAL_DELETE)) {
                        handleDeleteModal(e);
                    } else if (modalId.equals(MODAL_SERVER_NAME)) {
                        handleServerNameModal(e);
                    }
                } catch (Exception ex) {
                    LOGGER.error("Erreur lors du traitement du modal: {}", ex.getMessage(), ex);
                    e.getHook().sendMessage("❌ Une erreur est survenue: " + ex.getMessage()).queue();
                }
            }
        }

        private void handleAddModal(ModalInteractionEvent e) {
            String[] parts = e.getModalId().split(":");
            String sectionKey = parts[1];
            String emoji = e.getValue("emoji").getAsString();
            String name = e.getValue("name").getAsString();
            Document newItem = new Document().append("emoji", emoji).append("name", name);
            if (sectionKey.equals(SECTION_LINKS)) {
                String url = e.getValue("url").getAsString();
                newItem.append("url", url);
            } else {
                String description = e.getValue("description").getAsString();
                newItem.append("description", description);
            }
            updateSectionWithNewItem(sectionKey, newItem, name)
                    .thenAccept(v -> e.getHook().sendMessage("✅ Élément **" + name + "** ajouté avec succès.").queue(message -> {
                        message.delete().queueAfter(3, java.util.concurrent.TimeUnit.SECONDS);
                    }))
                    .exceptionally(error -> {
                        e.getHook().sendMessage("❌ Erreur lors de l'ajout : " + error.getMessage()).queue(message -> {
                            message.delete().queueAfter(3, java.util.concurrent.TimeUnit.SECONDS);
                        });
                        return null;
                    });
        }

        private void handleEditModal(ModalInteractionEvent e) {
            String[] parts = e.getModalId().split(":");
            String sectionKey = parts[1];
            String oldName = parts[2];
            String emoji = e.getValue("emoji").getAsString();
            String name = e.getValue("name").getAsString();
            Document updatedItem = new Document().append("emoji", emoji).append("name", name);

            if (sectionKey.equals(SECTION_LINKS)) {
                String url = e.getValue("url").getAsString();
                updatedItem.append("url", url);
            } else {
                String description = e.getValue("description").getAsString();
                updatedItem.append("description", description);
            }

            removeItemFromSection(sectionKey, oldName)
                    .thenCompose(v -> updateSectionWithNewItem(sectionKey, updatedItem, name))
                    .thenAccept(v -> e.getHook().sendMessage("✅ Élément **" + oldName + "** modifié avec succès.").queue(message -> {
                        message.delete().queueAfter(3, java.util.concurrent.TimeUnit.SECONDS);
                    }))
                    .exceptionally(error -> {
                        e.getHook().sendMessage("❌ Erreur lors de la modification : " + error.getMessage()).queue(message -> {
                            message.delete().queueAfter(3, java.util.concurrent.TimeUnit.SECONDS);
                        });
                        return null;
                    });
        }

        private void handleDeleteModal(ModalInteractionEvent e) {
            String[] parts = e.getModalId().split(":");
            String sectionKey = parts[1];
            String itemName = parts[2];
            String confirmation = e.getValue("confirm").getAsString();
            if (confirmation.equals("CONFIRMER")) {
                removeItemFromSection(sectionKey, itemName)
                        .thenAccept(v -> e.getHook().sendMessage("✅ L'élément **" + itemName + "** a été supprimé avec succès.").queue(message -> {
                            message.delete().queueAfter(3, java.util.concurrent.TimeUnit.SECONDS);
                        }))
                        .exceptionally(error -> {
                            e.getHook().sendMessage("❌ Erreur lors de la suppression : " + error.getMessage()).queue(message -> {
                                message.delete().queueAfter(3, java.util.concurrent.TimeUnit.SECONDS);
                            });
                            return null;
                        });
            } else {
                e.getHook().sendMessage("⚠️ Suppression annulée. Le texte de confirmation était incorrect.").queue(message -> {
                    message.delete().queueAfter(3, java.util.concurrent.TimeUnit.SECONDS);
                });
            }
        }

        private void handleServerNameModal(ModalInteractionEvent e) {
            String serverName = e.getValue("server_name").getAsString();

            ResourcesEmbedHandler.updateServerName(serverName)
                    .thenAccept(v -> e.getHook().sendMessage("✅ Le nom du serveur a été mis à jour en **" + serverName + "**.").queue(message -> {
                        message.delete().queueAfter(3, java.util.concurrent.TimeUnit.SECONDS);
                    }))
                    .exceptionally(error -> {
                        e.getHook().sendMessage("❌ Erreur lors de la mise à jour : " + error.getMessage()).queue(message -> {
                            message.delete().queueAfter(3, java.util.concurrent.TimeUnit.SECONDS);
                        });
                        return null;
                    });
        }
    }

    /**
     * Updates a section with a new item, replacing it if it already exists
     */
    private CompletableFuture<Void> updateSectionWithNewItem(String sectionKey, Document newItem, String itemName) {
        CompletableFuture<Void> future = new CompletableFuture<>();

        try {
            MongoCollection<Document> collection = Main.getMongoConnection().getDatabase().getCollection(COLLECTION_NAME);
            Document configDoc = collection.find(new Document("type", "config")).first();
            if (configDoc == null) {
                future.completeExceptionally(new IllegalStateException("Configuration non trouvée. Veuillez d'abord exécuter /resources update."));
                return future;
            }

            List<Document> items = configDoc.getList(sectionKey, Document.class, new ArrayList<>());

            // Check if item with this name already exists
            boolean found = false;
            for (int i = 0; i < items.size(); i++) {
                if (items.get(i).getString("name").equalsIgnoreCase(itemName)) {
                    items.set(i, newItem); // Replace existing item
                    found = true;
                    break;
                }
            }

            if (!found) {
                items.add(newItem); // Add new item
            }

            ResourcesEmbedHandler.updateSection(sectionKey, items)
                    .thenAccept(v -> future.complete(null))
                    .exceptionally(error -> {
                        future.completeExceptionally(error);
                        return null;
                    });
        } catch (Exception e) {
            future.completeExceptionally(e);
        }

        return future;
    }

    /**
     * Removes an item from a section
     */
    private CompletableFuture<Void> removeItemFromSection(String sectionKey, String itemName) {
        CompletableFuture<Void> future = new CompletableFuture<>();

        try {
            MongoCollection<Document> collection = Main.getMongoConnection().getDatabase().getCollection(COLLECTION_NAME);
            Document configDoc = collection.find(new Document("type", "config")).first();
            if (configDoc == null) {
                future.completeExceptionally(new IllegalStateException("Configuration non trouvée. Veuillez d'abord exécuter /resources update."));
                return future;
            }

            List<Document> items = configDoc.getList(sectionKey, Document.class, new ArrayList<>());

            // Remove the item with the matching name
            items.removeIf(item -> item.getString("name").equals(itemName));

            ResourcesEmbedHandler.updateSection(sectionKey, items)
                    .thenAccept(v -> future.complete(null))
                    .exceptionally(error -> {
                        future.completeExceptionally(error);
                        return null;
                    });
        } catch (Exception e) {
            future.completeExceptionally(e);
        }

        return future;
    }

    /**
     * Helper method to get display name for a section
     */
    private String getSectionDisplayName(String sectionKey) {
        switch (sectionKey) {
            case SECTION_ROLES:
                return "Rôles";
            case SECTION_CATEGORIES:
                return "Catégories";
            case SECTION_CHANNELS:
                return "Salons importants";
            case SECTION_LINKS:
                return "Liens utiles";
            default:
                return "Section";
        }
    }

    /**
     * Helper method to get item name for a section (singular form)
     */
    private String getSectionItemName(String sectionKey) {
        switch (sectionKey) {
            case SECTION_ROLES:
                return "Rôle";
            case SECTION_CATEGORIES:
                return "Catégorie";
            case SECTION_CHANNELS:
                return "Salon";
            case SECTION_LINKS:
                return "Lien";
            default:
                return "Élément";
        }
    }
}