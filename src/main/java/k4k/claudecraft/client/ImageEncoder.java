package k4k.claudecraft.client;

import net.minecraft.client.texture.NativeImage;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * Downscales captured frames and encodes them as PNG or JPEG for MCP image content.
 */
public final class ImageEncoder {
	private ImageEncoder() {
	}

	/**
	 * An encoded image.
	 *
	 * @param mimeType MIME type of {@code data}
	 * @param data     encoded bytes
	 * @param width    final width
	 * @param height   final height
	 */
	public record Encoded(String mimeType, byte[] data, int width, int height) {
	}

	/**
	 * Encodes and closes the image.
	 *
	 * @param image    captured frame; closed by this method
	 * @param maxWidth width to downscale to, keeping the aspect ratio; larger frames are never upscaled
	 * @param png      {@code true} for PNG, {@code false} for JPEG
	 * @param quality  JPEG quality in the 0..1 range
	 * @return the encoded image
	 * @throws IOException if encoding fails
	 */
	public static Encoded encode(NativeImage image, int maxWidth, boolean png, float quality) throws IOException {
		BufferedImage source;
		try {
			int width = image.getWidth();
			int height = image.getHeight();
			source = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
			source.setRGB(0, 0, width, height, image.makePixelArray(), 0, width);
		} finally {
			image.close();
		}

		BufferedImage scaled = scale(source, maxWidth);
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		if (png) {
			ImageIO.write(scaled, "png", out);
			return new Encoded("image/png", out.toByteArray(), scaled.getWidth(), scaled.getHeight());
		}
		ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
		try (ImageOutputStream stream = ImageIO.createImageOutputStream(out)) {
			ImageWriteParam param = writer.getDefaultWriteParam();
			param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
			param.setCompressionQuality(Math.max(0f, Math.min(1f, quality)));
			writer.setOutput(stream);
			writer.write(null, new IIOImage(scaled, null, null), param);
		} finally {
			writer.dispose();
		}
		return new Encoded("image/jpeg", out.toByteArray(), scaled.getWidth(), scaled.getHeight());
	}

	/**
	 * @return {@code source} downscaled to {@code maxWidth}, or {@code source} itself if it is not wider
	 */
	private static BufferedImage scale(BufferedImage source, int maxWidth) {
		if (maxWidth <= 0 || source.getWidth() <= maxWidth) {
			return source;
		}
		int height = Math.max(1, Math.round(source.getHeight() * (maxWidth / (float) source.getWidth())));
		BufferedImage scaled = new BufferedImage(maxWidth, height, BufferedImage.TYPE_INT_RGB);
		Graphics2D graphics = scaled.createGraphics();
		graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
		graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
		graphics.drawImage(source, 0, 0, maxWidth, height, null);
		graphics.dispose();
		return scaled;
	}
}
