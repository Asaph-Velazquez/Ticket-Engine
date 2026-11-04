import { useCallback, useEffect, useRef, useState } from "react";
import { QRCodeCanvas } from "qrcode.react";
import { formatDate, toMinorUnits } from "./lib/money.js";
import { downloadTicketHtml } from "./lib/exportTicket.js";
import { inspectToken, verifyTicket } from "./lib/verify.js";

const newId = () => globalThis.crypto?.randomUUID?.() ?? "";
const pad = (value) => String(value).padStart(2, "0");
function localDate(date) {
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}`;
}
function initialForm() {
  const start = new Date();
  start.setDate(start.getDate() + 1);
  start.setHours(19, 0, 0, 0);
  return {
    paymentId: newId(),
    eventId: newId(),
    buyerId: newId(),
    seatId: "",
    eventName: "",
    venue: "",
    starts: localDate(start),
    ends: localDate(new Date(start.getTime() + 7200000)),
    amount: "",
    currency: "MXN",
  };
}

class ApiError extends Error {
  constructor(status, code, message) {
    super(message);
    this.status = status;
    this.code = code;
  }
}
async function api(path, { token, body, signal } = {}) {
  let response;
  try {
    response = await fetch(path, {
      method: body ? "POST" : "GET",
      signal,
      cache: "no-store",
      headers: {
        ...(token ? { Authorization: `Bearer ${token}` } : {}),
        ...(body ? { "Content-Type": "application/json" } : {}),
      },
      ...(body ? { body: JSON.stringify(body) } : {}),
    });
  } catch (error) {
    if (error.name === "AbortError") throw error;
    throw new Error(
      "No se pudo conectar con el servidor. Revisa que los contenedores estén activos.",
    );
  }
  const result = await response.json().catch(() => ({}));
  if (!response.ok) {
    const messages = {
      PAYMENT_CONFLICT:
        "Este pago ya fue emitido con otros datos. Para otro boleto prepara un nuevo pago.",
      SEAT_ALREADY_ISSUED:
        "El asiento ya tiene un boleto para este evento. Elige un asiento distinto.",
      UNAUTHORIZED:
        "La clave administrativa no es válida o la sesión dejó de estar autorizada.",
    };
    throw new ApiError(
      response.status,
      result.code,
      messages[result.code] ??
        result.message ??
        `Error HTTP ${response.status}.`,
    );
  }
  return result;
}

function Icon({ name }) {
  const paths = {
    ticket: (
      <>
        <path d="M4 7h16v4a2 2 0 0 0 0 4v3H4v-3a2 2 0 0 0 0-4V7Z" />
        <path d="M15 8v2m0 3v2m0 2v1" />
      </>
    ),
    list: (
      <>
        <path d="M8 6h12M8 12h12M8 18h12" />
        <path d="M4 6h.01M4 12h.01M4 18h.01" />
      </>
    ),
    shield: (
      <>
        <path d="m12 3 8 3v6c0 5-8 9-8 9s-8-4-8-9V6l8-3Z" />
        <path d="m8 12 3 3 5-6" />
      </>
    ),
    arrow: (
      <>
        <path d="M5 12h14m-5-5 5 5-5 5" />
      </>
    ),
    lock: (
      <>
        <rect x="5" y="10" width="14" height="11" rx="2" />
        <path d="M8 10V7a4 4 0 0 1 8 0v3" />
      </>
    ),
  };
  return (
    <svg
      aria-hidden="true"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.7"
      strokeLinecap="round"
      strokeLinejoin="round"
    >
      {paths[name] ?? paths.ticket}
    </svg>
  );
}
function Field({ label, hint, ...props }) {
  const id = props.id ?? props.name;
  return (
    <label className="field" htmlFor={id}>
      <span>{label}</span>
      <input id={id} {...props} />
      {hint && <small>{hint}</small>}
    </label>
  );
}
function CodeBlock({ label, value, testId }) {
  const [copied, setCopied] = useState(false);
  const [error, setError] = useState("");
  return (
    <section className="code-block">
      <div className="code-heading">
        <h3>{label}</h3>
        <button
          className="text-button"
          type="button"
          onClick={async () => {
            try {
              await navigator.clipboard.writeText(value);
              setCopied(true);
              setError("");
            } catch {
              setError("Selecciona el texto para copiarlo manualmente.");
            }
          }}
        >
          {copied ? "Copiado" : "Copiar"}
        </button>
      </div>
      <pre tabIndex="0" data-testid={testId}>
        {value}
      </pre>
      {error && <small role="status">{error}</small>}
    </section>
  );
}

function TicketPreview({ ticket, onVerify, notify }) {
  const qrRef = useRef(null);
  const [technical, setTechnical] = useState(false);
  useEffect(() => {
    setTechnical(false);
  }, [ticket?.ticketId]);
  if (!ticket)
    return (
      <section className="preview-empty panel">
        <div className="empty-icon">
          <Icon name="ticket" />
        </div>
        <p className="eyebrow">TU BOLETO, EN UN SOLO LUGAR</p>
        <h2>La próxima entrada empieza aquí.</h2>
        <p>
          Emite un boleto sandbox o selecciona uno del registro para ver su
          pase, código QR y firma digital.
        </p>
        <div className="empty-rule" />
        <span>Sin boleto seleccionado</span>
      </section>
    );
  let inspection;
  try {
    inspection = inspectToken(ticket.token);
  } catch {
    inspection = null;
  }
  return (
    <div className="preview-stack">
      <article className="ticket-pass" aria-label="Boleto emitido">
        <header className="pass-header">
          <span className="eyebrow">TICKET ENGINE / ENTRADA DIGITAL</span>
          <span className="pass-status">{ticket.status}</span>
          <h2>{ticket.eventName}</h2>
          <p>{ticket.venue}</p>
        </header>
        <div className="pass-main">
          <div>
            <span className="label-small">FECHA Y HORA LOCAL</span>
            <strong>{formatDate(ticket.eventStartsAt)}</strong>
            <span className="label-small seat-label">ASIENTO</span>
            <div className="seat-number">{ticket.seatId}</div>
          </div>
          <div className="qr-wrap" data-testid="qr-code">
            <QRCodeCanvas
              ref={qrRef}
              value={ticket.token}
              size={768}
              level="M"
              marginSize={4}
              aria-label="Código QR del JWS firmado"
            />
            <small>JWS firmado · QR estático</small>
          </div>
        </div>
        <footer className="pass-footer">
          <div>
            <span className="label-small">IDENTIFICADOR DEL BOLETO</span>
            <code data-testid="ticket-id">{ticket.ticketId}</code>
          </div>
          <span>
            ES256
            <br />
            <small>P-256 / SHA-256</small>
          </span>
        </footer>
      </article>
      <div className="ticket-actions">
        <button
          className="button primary"
          type="button"
          onClick={() => {
            try {
              downloadTicketHtml(ticket, qrRef.current);
              notify(
                "HTML descargado. Ábrelo para imprimir o conservar el boleto.",
              );
            } catch (error) {
              notify(error.message, true);
            }
          }}
        >
          Descargar HTML
        </button>
        <button
          className="button secondary"
          type="button"
          onClick={() => onVerify(ticket.token)}
        >
          Verificar este boleto
        </button>
      </div>
      <p className="fine-print">
        El HTML conserva el token y la firma originales. Este QR es estático; el
        QR dinámico anticopia pertenece al Módulo 5.
      </p>
      <section className="panel technical">
        <button
          className="disclosure"
          type="button"
          aria-expanded={technical}
          onClick={() => setTechnical(!technical)}
        >
          Inspeccionar JWS <span>{technical ? "−" : "+"}</span>
        </button>
        {technical && (
          <div className="technical-body">
            <p className="fine-print">
              Decodificar permite inspeccionar. La autenticidad se comprueba con
              «Verificar este boleto».
            </p>
            <CodeBlock
              label="Encabezado protegido"
              value={
                JSON.stringify(inspection?.header, null, 2) ?? "No disponible"
              }
            />
            <CodeBlock
              label="Payload canónico"
              value={ticket.payloadJson}
              testId="ticket-payload"
            />
            <CodeBlock
              label="JWS original"
              value={ticket.token}
              testId="ticket-jws"
            />
            <CodeBlock label="SHA-256 del payload" value={ticket.payloadHash} />
            <CodeBlock label="Llave (kid)" value={ticket.keyId} />
          </div>
        )}
      </section>
    </div>
  );
}

function VerifyPanel({ value, setValue, system }) {
  const [result, setResult] = useState(null);
  const [busy, setBusy] = useState(false);
  const generation = useRef(0);
  useEffect(() => {
    generation.current++;
    setResult(null);
    setBusy(false);
  }, [value]);
  useEffect(
    () => () => {
      generation.current++;
    },
    [],
  );
  async function verify(event) {
    event.preventDefault();
    const current = ++generation.current;
    setBusy(true);
    setResult(null);
    try {
      const key = await api("/public-key");
      const checked = await verifyTicket(value.trim(), key, {
        issuer: system?.issuer,
        audience: system?.audience,
      });
      if (generation.current === current)
        setResult({ valid: true, payload: checked.payload });
    } catch (error) {
      if (generation.current === current)
        setResult({ valid: false, message: error.message });
    } finally {
      if (generation.current === current) setBusy(false);
    }
  }
  return (
    <section className="panel verifier">
      <div className="section-heading">
        <div>
          <p className="eyebrow">VERIFICACIÓN INDEPENDIENTE</p>
          <h2>Comprueba lo que recibiste.</h2>
        </div>
        <Icon name="shield" />
      </div>
      <p className="muted">
        WebCrypto verifica ES256 en este navegador con la llave pública del
        emisor. No se envía el boleto al endpoint de verificación.
      </p>
      <form onSubmit={verify}>
        <label className="field" htmlFor="verify-token">
          <span>JWS para verificar</span>
          <textarea
            id="verify-token"
            value={value}
            onChange={(event) => setValue(event.target.value)}
            rows="6"
            required
            maxLength={24000}
            spellCheck="false"
            placeholder="Pega los tres segmentos del JWS…"
          />
        </label>
        <button className="button primary" disabled={busy || !value.trim()}>
          {busy ? "Verificando firma…" : "Verificar firma local"}
        </button>
      </form>
      {result && (
        <div
          data-testid="verification-result"
          data-valid={String(result.valid)}
          role="status"
          className={`verification-result ${result.valid ? "success" : "danger"}`}
        >
          <strong>
            {result.valid ? "Firma y vigencia válidas" : "Boleto no válido"}
          </strong>
          <p>
            {result.valid
              ? "Firma ES256 auténtica, perfil y fechas comprobados con la llave pública descargada."
              : result.message}
          </p>
          {result.valid && (
            <dl>
              <dt>Evento</dt>
              <dd>{result.payload.eventName}</dd>
              <dt>Asiento</dt>
              <dd>{result.payload.seatId}</dd>
              <dt>Boleto</dt>
              <dd>{result.payload.jti}</dd>
            </dl>
          )}
        </div>
      )}
      <p className="fine-print">
        Esta comprobación no registra ni consume una entrada. El control de
        acceso y la auditoría corresponden al Módulo 6. La llave se obtiene del
        mismo origen; no se aceptan URLs de llaves incluidas en el token.
      </p>
    </section>
  );
}

export default function App() {
  const [view, setView] = useState("emission");
  const [session, setSession] = useState(null);
  const [keyInput, setKeyInput] = useState("");
  const [loggingIn, setLoggingIn] = useState(false);
  const [form, setForm] = useState(initialForm);
  const [page, setPage] = useState({
    items: [],
    total: null,
    limit: 20,
    offset: 0,
  });
  const [offset, setOffset] = useState(0);
  const [selected, setSelected] = useState(null);
  const [lastRequest, setLastRequest] = useState(null);
  const [busy, setBusy] = useState(false);
  const [refresh, setRefresh] = useState(0);
  const [apiState, setApiState] = useState("Sin comprobar");
  const [notice, setNotice] = useState(null);
  const [verifyValue, setVerifyValue] = useState("");
  const epoch = useRef(0);
  const requests = useRef(new Set());
  const emitting = useRef(false);
  const noticeRef = useRef(null);
  const notify = useCallback(
    (message, error = false) => setNotice({ message, error }),
    [],
  );
  const logout = useCallback(() => {
    epoch.current++;
    for (const controller of requests.current) controller.abort();
    requests.current.clear();
    emitting.current = false;
    setSession(null);
    setLoggingIn(false);
    setKeyInput("");
    setSelected(null);
    setLastRequest(null);
    setPage({ items: [], total: null, limit: 20, offset: 0 });
    setOffset(0);
    setForm(initialForm());
    setNotice(null);
    setVerifyValue("");
    setBusy(false);
    setApiState("Sin comprobar");
    setView("emission");
  }, []);

  useEffect(() => {
    if (!session) return;
    const version = epoch.current;
    let active = true,
      pending = false;
    const controllers = new Set();
    async function load() {
      if (pending) return;
      pending = true;
      const controller = new AbortController();
      controllers.add(controller);
      requests.current.add(controller);
      try {
        const data = await api(`/api/tickets?limit=20&offset=${offset}`, {
          token: session.token,
          signal: controller.signal,
        });
        if (active && epoch.current === version) {
          setPage(data);
          setApiState("Conectada");
        }
      } catch (error) {
        if (
          error.name !== "AbortError" &&
          active &&
          epoch.current === version
        ) {
          if (error.status === 401) {
            logout();
            notify(
              "La sesión dejó de estar autorizada. Ingresa de nuevo.",
              true,
            );
          } else {
            setApiState("Sin conexión");
            notify(error.message, true);
          }
        }
      } finally {
        pending = false;
        controllers.delete(controller);
        requests.current.delete(controller);
      }
    }
    load();
    const interval = setInterval(load, 5000);
    return () => {
      active = false;
      clearInterval(interval);
      for (const controller of controllers) {
        controller.abort();
        requests.current.delete(controller);
      }
    };
  }, [session, offset, refresh, logout, notify]);
  useEffect(
    () => () => {
      for (const controller of requests.current) controller.abort();
    },
    [],
  );

  async function login(event) {
    event.preventDefault();
    if (loggingIn) return;
    setLoggingIn(true);
    setNotice(null);
    const controller = new AbortController();
    requests.current.add(controller);
    const version = ++epoch.current;
    try {
      const token = keyInput.trim();
      const system = await api("/api/system", {
        token,
        signal: controller.signal,
      });
      if (epoch.current === version) {
        setSession({ token, system });
        setKeyInput("");
        setApiState("Conectada");
      }
    } catch (error) {
      if (error.name !== "AbortError" && epoch.current === version)
        notify(error.message, true);
    } finally {
      requests.current.delete(controller);
      if (epoch.current === version) setLoggingIn(false);
    }
  }

  async function emit(request) {
    if (!session || emitting.current) return;
    emitting.current = true;
    setBusy(true);
    setNotice(null);
    const version = epoch.current;
    const controller = new AbortController();
    requests.current.add(controller);
    setLastRequest(request);
    try {
      const result = await api("/api/demo/tickets", {
        token: session.token,
        body: request,
        signal: controller.signal,
      });
      if (epoch.current === version) {
        setSelected(result.ticket);
        setApiState("Conectada");
        setRefresh((value) => value + 1);
        notify(
          result.replayed
            ? "Pago recuperado: se conservó exactamente el boleto y su firma original."
            : "Boleto emitido y persistido. Para otro boleto, prepara un nuevo pago.",
        );
      }
    } catch (error) {
      if (epoch.current === version && error.name !== "AbortError") {
        if (error.status === 401) {
          logout();
          notify("La sesión dejó de estar autorizada.", true);
        } else notify(error.message, true);
      }
    } finally {
      requests.current.delete(controller);
      if (epoch.current === version) {
        emitting.current = false;
        setBusy(false);
      }
    }
  }
  function submit(event) {
    event.preventDefault();
    try {
      const start = new Date(form.starts),
        end = new Date(form.ends);
      if (
        !Number.isFinite(start.getTime()) ||
        !Number.isFinite(end.getTime()) ||
        end <= start ||
        end <= new Date()
      ) {
        throw new Error(
          "Revisa las fechas: el fin debe ser posterior al inicio y estar en el futuro.",
        );
      }
      if (!form.eventName.trim() || !form.venue.trim())
        throw new Error("Escribe el nombre del evento y el recinto.");
      const uuid =
        /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
      if (
        ![form.paymentId, form.eventId, form.buyerId].every(
          (value) =>
            uuid.test(value) &&
            value !== "00000000-0000-0000-0000-000000000000",
        )
      ) {
        throw new Error(
          "Revisa los UUID de pago, evento y comprador en los identificadores de integración.",
        );
      }
      emit({
        paymentId: form.paymentId,
        eventId: form.eventId,
        buyerId: form.buyerId,
        seatId: form.seatId,
        eventName: form.eventName,
        venue: form.venue,
        eventStartsAt: start.toISOString(),
        eventEndsAt: end.toISOString(),
        amountMinor: toMinorUnits(form.amount),
        currency: form.currency,
        paymentStatus: "PAID",
      });
    } catch (error) {
      notify(error.message, true);
      noticeRef.current?.focus();
    }
  }
  function update(event) {
    setForm((current) => ({
      ...current,
      [event.target.name]: event.target.value,
    }));
  }
  function verifySelected(token) {
    setVerifyValue(token);
    setView("verification");
    setNotice(null);
  }

  const titles = {
    emission: [
      "Emisión de boletos",
      "Del pago confirmado a una entrada firmada.",
    ],
    tickets: [
      "Registro de boletos",
      "Un registro inmutable, una firma por entrada.",
    ],
    verification: [
      "Verificación de firma",
      "La confianza también se puede comprobar.",
    ],
  };
  return (
    <div className="app-shell">
      <aside className="sidebar">
        <a className="brand" href="/" aria-label="Ticket Engine, inicio">
          <span className="brand-symbol">
            <Icon name="ticket" />
          </span>
          <span>
            Ticket Engine<small>MÓDULO 04</small>
          </span>
        </a>
        <div className="sidebar-line" />
        <span className="nav-label">ESPACIO DE TRABAJO</span>
        <nav aria-label="Navegación principal">
          {[
            ["emission", "Emisión", "ticket"],
            ["tickets", "Boletos", "list"],
            ["verification", "Verificación", "shield"],
          ].map(([key, label, icon]) => (
            <button
              key={key}
              data-testid={`nav-${key}`}
              aria-current={view === key ? "page" : undefined}
              className={view === key ? "nav-item active" : "nav-item"}
              onClick={() => {
                setView(key);
                setNotice(null);
              }}
            >
              <Icon name={icon} />
              {label}
              {view === key && <span className="nav-dot" />}
            </button>
          ))}
        </nav>
        <div className="sidebar-bottom">
          <div className="sidebar-note">
            <Icon name="lock" />
            <span>
              Emisión y firma digital<small>Java + Kotlin · ES256</small>
            </span>
          </div>
          <a href="/openapi.yaml" target="_blank" rel="noreferrer">
            Contrato de integración ↗
          </a>
        </div>
      </aside>
      <div className="workspace">
        <header className="topbar">
          <div className="breadcrumb">
            Sistema de boletos <span>/</span> Motor de emisión
          </div>
          <div className="topbar-right">
            <span
              className={`api-status ${apiState === "Conectada" ? "online" : ""}`}
            >
              <i />
              API {apiState.toLowerCase()}
            </span>
            {session && (
              <button
                className="text-button"
                data-testid="logout"
                onClick={logout}
              >
                Cerrar sesión
              </button>
            )}
          </div>
        </header>
        <main>
          <section className="page-heading">
            <div>
              <p className="eyebrow">CONFIANZA, EN CADA ENTRADA</p>
              <h1>{titles[view][0]}</h1>
              <p>{titles[view][1]}</p>
            </div>
            <span
              className={`mode-badge ${session?.system.demoEnabled ? "sandbox" : ""}`}
            >
              {session
                ? session.system.demoEnabled
                  ? "● Sandbox habilitado"
                  : "● Emisión demo deshabilitada"
                : "● Consola interna"}
            </span>
          </section>
          <section className="metrics" aria-label="Estado del módulo">
            <div>
              <span>Boletos emitidos</span>
              <strong>
                {page.total === null
                  ? "—"
                  : new Intl.NumberFormat("es-MX").format(page.total)}
              </strong>
              <small>
                {session
                  ? "Total persistido en este módulo"
                  : "Disponible al iniciar sesión"}
              </small>
            </div>
            <div>
              <span>Algoritmo de firma</span>
              <strong>
                ES256 <em>P-256</em>
              </strong>
              <small>ECDSA · SHA-256</small>
            </div>
            <div>
              <span>Almacenamiento</span>
              <strong>Inmutable</strong>
              <small>PostgreSQL · Solo inserción</small>
            </div>
          </section>
          {notice && (
            <div
              ref={noticeRef}
              tabIndex="-1"
              role={notice.error ? "alert" : "status"}
              className={`notice ${notice.error ? "error" : "success"}`}
            >
              {notice.message}
              <button
                className="text-button"
                aria-label="Cerrar aviso"
                onClick={() => setNotice(null)}
              >
                ×
              </button>
            </div>
          )}
          {view === "verification" ? (
            <VerifyPanel
              key={session ? "admin-verifier" : "public-verifier"}
              value={verifyValue}
              setValue={setVerifyValue}
              system={session?.system}
            />
          ) : !session ? (
            <div className="emission-layout">
              <section className="panel login-card">
                <div className="section-heading">
                  <div>
                    <p className="eyebrow">ACCESO RESTRINGIDO</p>
                    <h2>Entra a tu consola.</h2>
                  </div>
                  <Icon name="lock" />
                </div>
                <p className="muted">
                  La emisión y el registro están reservados al equipo del
                  módulo.
                </p>
                <form data-testid="login-form" onSubmit={login}>
                  <Field
                    label="Clave administrativa"
                    name="admin-token"
                    type="password"
                    value={keyInput}
                    onChange={(event) => setKeyInput(event.target.value)}
                    required
                    autoComplete="off"
                    spellCheck="false"
                    hint="Usa la clave generada para este entorno en .secrets/admin-token."
                  />
                  <button className="button primary" disabled={loggingIn}>
                    {loggingIn ? "Comprobando acceso…" : "Entrar a la consola"}
                    <Icon name="arrow" />
                  </button>
                </form>
                <p className="fine-print">
                  La clave se conserva solo en memoria durante la sesión. La
                  verificación pública está disponible sin entrar.
                </p>
              </section>
              <TicketPreview ticket={null} />
            </div>
          ) : view === "tickets" ? (
            <div className="emission-layout">
              <section className="panel registry">
                <div className="section-heading">
                  <div>
                    <p className="eyebrow">HISTORIAL INMUTABLE</p>
                    <h2>Entradas emitidas</h2>
                  </div>
                  <button
                    className="text-button"
                    onClick={() => setRefresh((value) => value + 1)}
                  >
                    Actualizar
                  </button>
                </div>
                <p className="fine-print">
                  Actualización automática cada 5 segundos. Se muestran hasta 20
                  por página.
                </p>
                {page.items.length ? (
                  <ul className="ticket-list">
                    {page.items.map((ticket) => (
                      <li key={ticket.ticketId}>
                        <button
                          type="button"
                          aria-label={`Ver boleto ${ticket.ticketId}`}
                          data-testid={`ticket-row-${ticket.ticketId}`}
                          aria-pressed={selected?.ticketId === ticket.ticketId}
                          onClick={() => setSelected(ticket)}
                        >
                          <span className="list-seat">{ticket.seatId}</span>
                          <span className="list-info">
                            <strong>{ticket.eventName}</strong>
                            <small>{formatDate(ticket.eventStartsAt)}</small>
                            <code>{ticket.ticketId.slice(0, 8)}</code>
                          </span>
                          <span aria-hidden="true">↗</span>
                        </button>
                      </li>
                    ))}
                  </ul>
                ) : (
                  <div className="list-empty">
                    <Icon name="ticket" />
                    <h3>
                      {page.total === null
                        ? "Cargando registro…"
                        : "Todavía no hay boletos en esta página."}
                    </h3>
                    <p>
                      Los boletos aparecerán aquí después de una emisión
                      confirmada.
                    </p>
                  </div>
                )}
                <div className="pagination">
                  <button
                    className="button secondary"
                    disabled={offset === 0}
                    onClick={() =>
                      setOffset((value) => Math.max(0, value - 20))
                    }
                  >
                    Anterior
                  </button>
                  <span>
                    {page.total
                      ? `${offset + 1}–${Math.min(offset + 20, page.total)} de ${page.total}`
                      : "Sin registros"}
                  </span>
                  <button
                    className="button secondary"
                    disabled={page.total === null || offset + 20 >= page.total}
                    onClick={() => setOffset((value) => value + 20)}
                  >
                    Siguiente
                  </button>
                </div>
              </section>
              <TicketPreview
                ticket={selected}
                onVerify={verifySelected}
                notify={notify}
              />
            </div>
          ) : (
            <div className="emission-layout">
              <section className="panel emission-panel">
                <div className="section-heading">
                  <div>
                    <p className="eyebrow">NUEVA EMISIÓN SANDBOX</p>
                    <h2>Prepara una entrada.</h2>
                  </div>
                  <span className="step-mark">01 / 01</span>
                </div>
                <p className="muted">
                  Simula una confirmación de pago. El boleto se firma y persiste
                  realmente; no se cobra dinero.
                </p>
                {!session.system.demoEnabled && (
                  <p className="notice error">
                    La emisión demo está deshabilitada en este entorno.
                  </p>
                )}
                <form data-testid="emission-form" onSubmit={submit}>
                  <Field
                    label="Nombre del evento"
                    name="eventName"
                    value={form.eventName}
                    onChange={update}
                    maxLength={140}
                    required
                    placeholder="Ej. Noche de concierto"
                  />
                  <Field
                    label="Recinto"
                    name="venue"
                    value={form.venue}
                    onChange={update}
                    maxLength={140}
                    required
                    placeholder="Ej. Auditorio Central"
                  />
                  <div className="field-row">
                    <Field
                      label="Asiento"
                      name="seatId"
                      value={form.seatId}
                      onChange={update}
                      pattern={"[A-Za-z0-9][A-Za-z0-9._:\\-]{0,63}"}
                      maxLength={64}
                      required
                      placeholder="Ej. A-12"
                    />
                    <label className="field" htmlFor="currency">
                      <span>Moneda</span>
                      <select
                        id="currency"
                        name="currency"
                        value={form.currency}
                        onChange={update}
                      >
                        <option>MXN</option>
                        <option>USD</option>
                        <option>EUR</option>
                      </select>
                    </label>
                  </div>
                  <Field
                    label="Importe"
                    name="amount"
                    value={form.amount}
                    onChange={update}
                    inputMode="decimal"
                    required
                    placeholder="1250.00"
                    hint="Importe total con hasta dos decimales. Conversión exacta a centavos."
                  />
                  <div className="field-row">
                    <Field
                      label="Inicio del evento"
                      name="starts"
                      type="datetime-local"
                      value={form.starts}
                      onChange={update}
                      required
                    />
                    <Field
                      label="Fin del evento"
                      name="ends"
                      type="datetime-local"
                      value={form.ends}
                      onChange={update}
                      required
                    />
                  </div>
                  <p className="fine-print">
                    Fechas en la zona horaria de tu navegador. El asiento debe
                    venir del sistema de venta; este panel no consulta
                    disponibilidad.
                  </p>
                  <details className="integration-details">
                    <summary>Identificadores de integración</summary>
                    <Field
                      label="ID del pago"
                      name="paymentId"
                      value={form.paymentId}
                      onChange={update}
                      required
                    />
                    <Field
                      label="ID del evento"
                      name="eventId"
                      value={form.eventId}
                      onChange={update}
                      required
                    />
                    <Field
                      label="ID del comprador"
                      name="buyerId"
                      value={form.buyerId}
                      onChange={update}
                      required
                    />
                    <p className="fine-print">
                      UUID de prueba generados al preparar el formulario.
                      Conserva el pago para reintentar; usa «Nuevo pago» para
                      otro boleto.
                    </p>
                  </details>
                  <button
                    className="button primary submit-button"
                    disabled={busy || !session.system.demoEnabled}
                  >
                    {busy ? "Emitiendo boleto…" : "Emitir boleto sandbox"}
                    <Icon name="arrow" />
                  </button>
                  <div className="secondary-actions">
                    <button
                      className="text-button"
                      type="button"
                      disabled={
                        busy || !lastRequest || !session.system.demoEnabled
                      }
                      onClick={() => emit(lastRequest)}
                    >
                      Reenviar último pago
                    </button>
                    <button
                      className="text-button"
                      type="button"
                      disabled={busy}
                      onClick={() => {
                        setForm((current) => ({
                          ...current,
                          paymentId: newId(),
                          seatId: "",
                        }));
                        notify(
                          "Nuevo ID de pago preparado. Selecciona el asiento y revisa los datos antes de emitir.",
                        );
                      }}
                    >
                      Nuevo pago
                    </button>
                  </div>
                  {lastRequest && (
                    <p className="fine-print">
                      Último pago enviado:{" "}
                      <code>{lastRequest.paymentId.slice(0, 8)}…</code>
                    </p>
                  )}
                </form>
              </section>
              <TicketPreview
                ticket={selected}
                onVerify={verifySelected}
                notify={notify}
              />
            </div>
          )}
          <footer className="workspace-footer">
            <span>
              Ticket Engine <b>/</b> Motor de emisión y firma digital
            </span>
            <span>Módulo 04 · Sistema distribuido</span>
          </footer>
        </main>
      </div>
    </div>
  );
}
