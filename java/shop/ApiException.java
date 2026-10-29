package shop;

/** Ошибка уровня API: содержит HTTP-статус и сообщение для клиента. */
public class ApiException extends RuntimeException {
    public final int httpStatus;

    public ApiException(int httpStatus, String message) {
        super(message);
        this.httpStatus = httpStatus;
    }
}
