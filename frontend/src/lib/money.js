export function toMinorUnits(value) {
  const text = String(value).trim();
  if (!/^\d{1,11}(?:[.,]\d{1,2})?$/.test(text)) {
    throw new Error("Ingresa un importe positivo con hasta dos decimales.");
  }
  const [whole, fraction = ""] = text.replace(",", ".").split(".");
  const amount = BigInt(whole) * 100n + BigInt(fraction.padEnd(2, "0"));
  if (amount > 9_999_999_999_999n)
    throw new Error("El importe supera el límite permitido.");
  return Number(amount);
}

export function formatDate(value) {
  const date = new Date(value);
  return Number.isNaN(date.getTime())
    ? String(value)
    : new Intl.DateTimeFormat("es-MX", {
        dateStyle: "medium",
        timeStyle: "short",
      }).format(date);
}
