package ca.lwi.trqcbot.donations;

import io.github.cdimascio.dotenv.Dotenv;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.components.text.TextInput;
import net.dv8tion.jda.api.interactions.components.text.TextInputStyle;
import net.dv8tion.jda.api.interactions.modals.Modal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;

public class DonationsManager extends ListenerAdapter {

    private static final Logger LOGGER = LoggerFactory.getLogger(DonationsManager.class);

    private final String channelId;

    public DonationsManager() {
        Dotenv dotenv = Dotenv.load();
        this.channelId = dotenv.get("CHANNEL_HALL_OF_FAME_ID");
    }

    public void handleUpdateDonors(SlashCommandInteractionEvent e) {
        boolean forceNew = e.getOption("force_new") != null && Objects.requireNonNull(e.getOption("force_new")).getAsBoolean();
        DonationsHandler.sendPermanentDonorsMessage(this.channelId, forceNew)
                .thenRun(() -> e.getHook().editOriginal("✅ Le message a été mis à jour.").queue())
                .exceptionally(error -> {
                    e.getHook().editOriginal("Erreur lors de la mise à jour du tableau des donateurs: " + error.getMessage()).queue();
                    return null;
                });
    }

    public void handleAddDonation(SlashCommandInteractionEvent e) {
        // Vérifier si le donateur est spécifié
        String donorName = e.getOption("donateur") != null ? Objects.requireNonNull(e.getOption("donateur")).getAsString() : null;
        Integer amount = e.getOption("montant") != null ? Objects.requireNonNull(e.getOption("montant")).getAsInt() : null;

        // Si le montant est spécifié et le donateur aussi, on traite directement
        if (donorName != null && amount != null) {
            if (amount <= 0) {
                e.reply("Le montant doit être supérieur à 0.").setEphemeral(true).queue();
                return;
            }

            // Vérifier si le donateur existe
            if (!DonationsHandler.getDonorNamesList().contains(donorName)) {
                e.reply("Ce donateur n'existe pas dans la base de données. Utilisez la commande sans spécifier de donateur pour en créer un nouveau.").setEphemeral(true).queue();
                return;
            }

            // Ajouter directement la donation
            boolean success = DonationsHandler.addDonationToExistingDonor(donorName, amount);
            if (success) {
                updateAndSendMessage(e, amount, donorName, "ajouté");
            } else {
                e.reply("Erreur lors de l'ajout du don. Vérifiez que le donateur existe.").setEphemeral(true).queue();
            }
            return;
        }

        // Si un donateur est spécifié (mais pas le montant), vérifier qu'il existe
        if (donorName != null) {
            if (DonationsHandler.getDonorNamesList().contains(donorName)) {
                // Ouvrir un modal pour le montant si le donateur existe
                TextInput amountInput = TextInput.create("amount", "Montant du don", TextInputStyle.SHORT)
                        .setPlaceholder("Entrez le montant (nombre entier)")
                        .setMinLength(1)
                        .setMaxLength(5)
                        .setRequired(true)
                        .build();
                Modal modal = Modal.create("donation-amount-modal-" + donorName, "Ajouter un don pour " + donorName)
                        .addActionRow(amountInput)
                        .build();
                e.replyModal(modal).queue();
            } else {
                e.reply("Ce donateur n'existe pas dans la base de données. Utilisez la commande sans spécifier de donateur pour en créer un nouveau.").setEphemeral(true).queue();
            }
            return;
        }

        // Si aucun donateur n'est spécifié, on crée un nouveau donateur (ouvrir modal sans pré-remplir)
        TextInput nameInput = TextInput.create("name", "Nom du donateur", TextInputStyle.SHORT)
                .setPlaceholder("Entrez le nom du donateur")
                .setMinLength(1)
                .setMaxLength(50)
                .setRequired(true)
                .build();

        TextInput amountInput = TextInput.create("amount", "Montant du don", TextInputStyle.SHORT)
                .setPlaceholder("Entrez le montant (nombre entier)")
                .setMinLength(1)
                .setMaxLength(5)
                .setRequired(true)
                .build();

        Modal modal = Modal.create("new-donor-modal", "Ajouter un nouveau donateur")
                .addActionRow(nameInput)
                .addActionRow(amountInput)
                .build();

        e.replyModal(modal).queue();
    }

    public void handleRemoveDonation(SlashCommandInteractionEvent e) {
        String donorName = Objects.requireNonNull(e.getOption("donateur")).getAsString();
        Integer amount = e.getOption("montant") != null ? Objects.requireNonNull(e.getOption("montant")).getAsInt() : null;
        List<String> donorNames = DonationsHandler.getDonorNamesList();
        if (!donorNames.contains(donorName)) {
            e.reply("Ce donateur n'existe pas dans la base de données.").setEphemeral(true).queue();
            return;
        }
        if (amount != null) {
            if (amount <= 0) {
                e.reply("Le montant doit être supérieur à 0.").setEphemeral(true).queue();
                return;
            }

            // Retirer directement la donation
            boolean success = DonationsHandler.removeDonationFromExistingDonor(donorName, amount);
            if (success) {
                updateAndSendMessage(e, amount, donorName, "retiré");
            } else {
                e.reply("Erreur lors du retrait du don. Vérifiez que le donateur existe.").setEphemeral(true).queue();
            }
            return;
        }

        TextInput amountInput = TextInput.create("amount", "Montant à retirer", TextInputStyle.SHORT)
                .setPlaceholder("Entrez le montant (nombre entier)")
                .setMinLength(1)
                .setMaxLength(5)
                .setRequired(true)
                .build();
        Modal modal = Modal.create("donation-remove-modal-" + donorName, "Retirer un don pour " + donorName).addActionRow(amountInput).build();
        e.replyModal(modal).queue();
    }

    @Override
    public void onStringSelectInteraction(StringSelectInteractionEvent event) {
        if (event.getComponentId().equals("donor-select")) {
            String selectedDonor = event.getValues().getFirst();
            TextInput amountInput = TextInput.create("amount", "Montant du don", TextInputStyle.SHORT)
                    .setPlaceholder("Entrez le montant (nombre entier)")
                    .setMinLength(1)
                    .setMaxLength(5)
                    .setRequired(true)
                    .build();
            Modal modal = Modal.create("donation-amount-modal-" + selectedDonor, "Ajouter un don pour " + selectedDonor).addActionRow(amountInput).build();
            event.replyModal(modal).queue();
        }
    }

    @Override
    public void onModalInteraction(ModalInteractionEvent e) {
        if (e.getModalId().equals("new-donor-modal")) {
            handleNewDonorModal(e);
        } else if (e.getModalId().startsWith("donation-amount-modal-")) {
            handleDonationAmountModal(e);
        } else if (e.getModalId().startsWith("donation-remove-modal-")) {
            handleDonationRemoveModal(e);
        }
    }

    private void handleDonationAmountModal(ModalInteractionEvent e) {
        String amountStr = Objects.requireNonNull(e.getValue("amount")).getAsString();
        String donorName = "";
        if (e.getModalId().startsWith("donation-amount-modal-")) {
            donorName = e.getModalId().substring("donation-amount-modal-".length());
        } else {
            e.reply("Erreur: impossible d'identifier le donateur.").setEphemeral(true).queue();
            return;
        }
        try {
            int amount = Integer.parseInt(amountStr);
            if (amount <= 0) {
                e.reply("Le montant doit être supérieur à 0.").setEphemeral(true).queue();
                return;
            }
            boolean success = DonationsHandler.addDonationToExistingDonor(donorName, amount);
            if (success) {
                updateAndSendMessage(e, amount, donorName, "ajouté");
            } else {
                e.reply("Erreur lors de l'ajout du don. Vérifiez que le donateur existe.").setEphemeral(true).queue();
            }
        } catch (NumberFormatException ex) {
            e.reply("Le montant doit être un nombre entier valide.").setEphemeral(true).queue();
        }
    }

    private void handleNewDonorModal(ModalInteractionEvent e) {
        String donorName = Objects.requireNonNull(e.getValue("name")).getAsString();
        String amountStr = Objects.requireNonNull(e.getValue("amount")).getAsString();

        try {
            int amount = Integer.parseInt(amountStr);
            if (amount <= 0) {
                e.reply("Le montant doit être supérieur à 0.").setEphemeral(true).queue();
                return;
            }

            // Vérifier si le donateur existe déjà
            List<String> donorNames = DonationsHandler.getDonorNamesList();
            if (donorNames.contains(donorName)) {
                boolean success = DonationsHandler.addDonationToExistingDonor(donorName, amount);
                if (success) {
                    e.reply("✅ Le donateur **" + donorName + "** existe déjà. Le don de **" + amount + " $** a été ajouté à son total !").setEphemeral(true).queue();
                    DonationsHandler.sendPermanentDonorsMessage(this.channelId, false)
                            .exceptionally(error -> {
                                LOGGER.error("Erreur lors de la mise à jour du tableau des donateurs: {}", error.getMessage());
                                return null;
                            });
                } else {
                    e.reply("Erreur lors de l'ajout du don au donateur existant.").setEphemeral(true).queue();
                }
                return;
            }

            // Sinon, on crée un nouveau donateur
            boolean success = DonationsHandler.addNewDonor(donorName, amount);
            if (success) {
                updateAndSendMessage(e, amount, donorName, "ajouté");
            } else {
                e.reply("Erreur lors de l'ajout du nouveau donateur.").setEphemeral(true).queue();
            }
        } catch (NumberFormatException ex) {
            e.reply("Le montant doit être un nombre entier valide.").setEphemeral(true).queue();
        }
    }

    private void handleDonationRemoveModal(ModalInteractionEvent e) {
        String amountStr = Objects.requireNonNull(e.getValue("amount")).getAsString();
        String donorName = "";
        if (e.getModalId().startsWith("donation-remove-modal-")) {
            donorName = e.getModalId().substring("donation-remove-modal-".length());
        } else {
            e.reply("Erreur: impossible d'identifier le donateur.").setEphemeral(true).queue();
            return;
        }
        try {
            int amount = Integer.parseInt(amountStr);
            if (amount <= 0) {
                e.reply("Le montant à retirer doit être supérieur à 0.").setEphemeral(true).queue();
                return;
            }
            boolean success = DonationsHandler.removeDonationFromExistingDonor(donorName, amount);
            if (success) {
                e.reply("✅ Le don de **" + amount + " $** a été retiré du total de **" + donorName + "** !").setEphemeral(true).queue();
                DonationsHandler.sendPermanentDonorsMessage(this.channelId, false)
                        .exceptionally(error -> {
                            LOGGER.error("Erreur lors de la mise à jour du tableau des donateurs: {}", error.getMessage());
                            return null;
                        });
            } else {
                e.reply("Erreur lors du retrait du don. Vérifiez que le donateur existe.").setEphemeral(true).queue();
            }
        } catch (NumberFormatException ex) {
            e.reply("Le montant doit être un nombre entier valide.").setEphemeral(true).queue();
        }
    }

    private void updateAndSendMessage(SlashCommandInteractionEvent e, int amount, String donorName, String action) {
        e.reply("✅ Le don de **" + amount + " $** de **" + donorName + "** a été " + action + " !").setEphemeral(true).queue();
        DonationsHandler.sendPermanentDonorsMessage(this.channelId, false)
                .exceptionally(error -> {
                    LOGGER.error("Erreur lors de la mise à jour du tableau des donateurs: {}", error.getMessage());
                    return null;
                });
    }

    private void updateAndSendMessage(ModalInteractionEvent e, int amount, String donorName, String action) {
        e.reply("✅ Le don de **" + amount + " $** de **" + donorName + "** a été " + action + " !").setEphemeral(true).queue();
        DonationsHandler.sendPermanentDonorsMessage(this.channelId, false)
                .exceptionally(error -> {
                    LOGGER.error("Erreur lors de la mise à jour du tableau des donateurs: {}", error.getMessage());
                    return null;
                });
    }

    @Override
    public void onButtonInteraction(ButtonInteractionEvent e) {
        String componentId = e.getComponentId();
        if (componentId.equals("donors_view")) {
            DonationsHandler.handleViewButton(e);
            return;
        }
        if (componentId.startsWith("donors_")) {
            String[] parts = componentId.split("_");
            if (parts.length >= 3) {
                String action = parts[1];
                String userId = parts[2];
                if (userId.equals(e.getUser().getId())) {
                    DonationsHandler.handlePaginationButton(e, action);
                } else {
                    e.reply("Ces boutons sont destinés à un autre utilisateur.").setEphemeral(true).queue();
                }
            }
        }
    }
}
