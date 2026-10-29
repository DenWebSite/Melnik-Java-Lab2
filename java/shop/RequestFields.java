package shop;

import java.math.BigDecimal;
import java.util.Map;

/** Чтение и проверка полей из JSON-объекта запроса. */
public final class RequestFields {
    private RequestFields() {}

    public static int readInteger(Map<?, ?> jsonObject, String fieldName) {
        Object fieldValue = jsonObject.get(fieldName);
        if (!(fieldValue instanceof BigDecimal number))
            throw new ApiException(400, "Поле '" + fieldName + "' обязательно и должно быть целым числом");
        try {
            return number.intValueExact();
        } catch (ArithmeticException notAnInteger) {
            throw new ApiException(400, "Поле '" + fieldName + "' должно быть целым числом");
        }
    }

    public static BigDecimal readDecimal(Map<?, ?> jsonObject, String fieldName) {
        Object fieldValue = jsonObject.get(fieldName);
        if (!(fieldValue instanceof BigDecimal number))
            throw new ApiException(400, "Поле '" + fieldName + "' обязательно и должно быть числом");
        return number;
    }
}
