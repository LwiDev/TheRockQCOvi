package ca.lwi.trqcbot.utils;

import ca.lwi.trqcbot.commands.list.ComTeam;
import net.dv8tion.jda.api.entities.User;
import org.apache.batik.anim.dom.SAXSVGDocumentFactory;
import org.apache.batik.transcoder.TranscoderInput;
import org.apache.batik.transcoder.TranscoderOutput;
import org.apache.batik.transcoder.image.ImageTranscoder;
import org.apache.batik.transcoder.image.PNGTranscoder;
import org.apache.batik.util.XMLResourceDescriptor;
import org.w3c.dom.svg.SVGDocument;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLConnection;
import java.util.Objects;

public class ImageUtils {

    public static BufferedImage loadSVG(String svgUrl, int maxDimension) throws IOException {
        try {
            // Parser le document SVG
            String parser = XMLResourceDescriptor.getXMLParserClassName();
            SAXSVGDocumentFactory factory = new SAXSVGDocumentFactory(parser);

            // URL Connection avec User-Agent
            URLConnection connection = new URI(svgUrl).toURL().openConnection();
            connection.setRequestProperty("User-Agent", "bot emily-bot");

            // Charger le document SVG
            SVGDocument svgDocument = factory.createSVGDocument(svgUrl, connection.getInputStream());

            // Transcodage en image PNG
            PNGTranscoder transcoder = new PNGTranscoder();
            transcoder.addTranscodingHint(ImageTranscoder.KEY_WIDTH, (float) maxDimension);
            transcoder.addTranscodingHint(ImageTranscoder.KEY_HEIGHT, (float) maxDimension);

            // Input SVG
            TranscoderInput input = new TranscoderInput(svgDocument);

            // Output vers ByteArrayOutputStream
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            TranscoderOutput output = new TranscoderOutput(outputStream);

            // Transcoder de SVG vers PNG
            transcoder.transcode(input, output);

            // Convertir le résultat en BufferedImage
            ByteArrayInputStream inputStream = new ByteArrayInputStream(outputStream.toByteArray());
            return ImageIO.read(inputStream);

        } catch (Exception e) {
            throw new IOException("Erreur lors du chargement du SVG: " + e.getMessage(), e);
        }
    }

    // Modifiez la méthode loadImage pour gérer les SVG
    public static BufferedImage loadImage(String imagePath, int maxDimension) throws IOException, URISyntaxException {
        if (imagePath == null || imagePath.isEmpty()) throw new IOException("Le chemin de l'image est vide ou null");

        // Si c'est une image SVG
        if (imagePath.toLowerCase().endsWith(".svg")) {
            // Définir une taille légèrement plus grande pour une meilleure qualité lors du redimensionnement final
            return loadSVG(imagePath, maxDimension);
        }

        // Pour les autres formats d'image (PNG, JPG, etc.)
        BufferedImage image;
        if (imagePath.startsWith("http://") || imagePath.startsWith("https://")) {
            URI uri = new URI(imagePath);
            URLConnection connection = uri.toURL().openConnection();
            connection.setRequestProperty("User-Agent", "bot emily-bot");

            image = ImageIO.read(connection.getInputStream());

            // Convertir en ARGB si nécessaire
            if (image != null && image.getType() != BufferedImage.TYPE_INT_ARGB) {
                BufferedImage argbImage = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_ARGB);
                Graphics2D g = argbImage.createGraphics();
                g.drawImage(image, 0, 0, null);
                g.dispose();
                image = argbImage;
            }
        } else {
            image = ImageIO.read(new File(imagePath));

            // Convertir en ARGB si nécessaire
            if (image != null && image.getType() != BufferedImage.TYPE_INT_ARGB) {
                BufferedImage argbImage = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_ARGB);
                Graphics2D g = argbImage.createGraphics();
                g.drawImage(image, 0, 0, null);
                g.dispose();
                image = argbImage;
            }
        }

        if (image == null) throw new IOException("Impossible de charger l'image: " + imagePath);
        return image;
    }

    public static BufferedImage getUserAvatar(User user) throws IOException, URISyntaxException {
        URLConnection connection = new URI(user.getAvatarUrl() != null ? user.getAvatarUrl() : user.getDefaultAvatarUrl()).toURL().openConnection();
        connection.setRequestProperty("User-Agent", "bot emily-bot");
        BufferedImage profileImg;
        try {
            profileImg = ImageIO.read(connection.getInputStream());
        } catch (Exception ignored) {
            profileImg = ImageIO.read(Objects.requireNonNull(ComTeam.class.getClassLoader().getResource("default_profile.jpg")));
        }
        return profileImg;
    }
}
