"""Retry connection establishment only, before an HTTP model request can be sent."""
import errno
import http.client
import ssl
import time
import urllib.request

_TRANSIENT = {errno.ECONNABORTED, errno.ECONNRESET, errno.ECONNREFUSED,
              errno.ETIMEDOUT, errno.ENETDOWN, errno.ENETUNREACH, errno.EHOSTUNREACH}


def _transient(error):
    if isinstance(error, ssl.SSLCertVerificationError):
        return False
    if isinstance(error, ssl.SSLError):
        return any(marker in str(error) for marker in ("TLSV1_ALERT_INTERNAL_ERROR", "UNEXPECTED_EOF_WHILE_READING"))
    return isinstance(error, TimeoutError) or getattr(error, "errno", None) in _TRANSIENT


class _ConnectRetry:
    def __init__(self, *args, session, **kwargs):
        self._aster_session = session
        super().__init__(*args, **kwargs)

    def connect(self):
        # HTTPConnection.send calls connect before writing the request headers/body.
        # An exception from request/getresponse/read is deliberately never replayed.
        tunnel = (self._tunnel_host, self._tunnel_port, dict(self._tunnel_headers))
        read_timeout = self.timeout
        for attempt in range(3):
            self._aster_session.check_cancelled()
            self.timeout = min(float(read_timeout), 10) if isinstance(read_timeout, (int, float)) else 10
            try:
                super().connect()
                if self.sock is not None:
                    self.sock.settimeout(read_timeout if isinstance(read_timeout, (int, float)) else None)
                self.timeout = read_timeout
                return
            except OSError as error:
                self.timeout = read_timeout
                if self.sock is not None:
                    self.sock.close()
                    self.sock = None
                self._tunnel_host, self._tunnel_port, self._tunnel_headers = tunnel
                if attempt == 2 or not _transient(error):
                    raise
                if isinstance(error, ssl.SSLError) and "TLSV1_ALERT_INTERNAL_ERROR" in str(error):
                    context = ssl.create_default_context()
                    context.minimum_version = context.maximum_version = ssl.TLSVersion.TLSv1_2
                    self._context = context
                self._aster_session.emit("status", text="连接尚未建立，正在重连（%d/2）" % (attempt + 1),
                                         step=getattr(self._aster_session, "steps", 0))
                end = time.monotonic() + .2 * (attempt + 1)
                while time.monotonic() < end:
                    self._aster_session.check_cancelled()
                    time.sleep(.05)


class _HTTPSConnection(_ConnectRetry, http.client.HTTPSConnection):
    pass


class _HTTPConnection(_ConnectRetry, http.client.HTTPConnection):
    pass


class _HTTPSHandler(urllib.request.HTTPSHandler):
    def __init__(self, session, context):
        super().__init__(context=context)
        self.session = session

    def https_open(self, request):
        return self.do_open(lambda host, **kwargs: _HTTPSConnection(host, session=self.session, **kwargs),
                            request, context=self._context)


class _HTTPHandler(urllib.request.HTTPHandler):
    def __init__(self, session):
        super().__init__()
        self.session = session

    def http_open(self, request):
        return self.do_open(lambda host, **kwargs: _HTTPConnection(host, session=self.session, **kwargs), request)


class _NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, msg, headers, newurl):
        # A redirect must not forward provider credentials to a different host.
        return None


def open_response(request, session, context, timeout):
    # Keep urllib's explicit/environment proxy support and standard CA verification.
    opener = urllib.request.build_opener(_HTTPHandler(session), _HTTPSHandler(session, context), _NoRedirect())
    return opener.open(request, timeout=timeout)
