package br.com.sergio.sapiensia.product;

/** The image formats accepted for a product cover, identified only by their bytes. */
public enum CoverFormat {
    PNG("image/png", "png"),
    JPEG("image/jpeg", "jpg"),
    WEBP("image/webp", "webp");

    private final String mediaType;
    private final String extension;

    CoverFormat(String mediaType, String extension) {
        this.mediaType = mediaType;
        this.extension = extension;
    }

    public String mediaType() {
        return mediaType;
    }

    public String extension() {
        return extension;
    }
}
