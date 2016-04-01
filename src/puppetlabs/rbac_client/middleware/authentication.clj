(ns puppetlabs.rbac-client.middleware.authentication
  (:require [clojure.tools.logging :as log]
            [puppetlabs.kitchensink.json :as json]
            [puppetlabs.rbac-client.protocols.rbac :refer [valid-token->subject cert-whitelisted?]]
            [puppetlabs.trapperkeeper.services :refer [service-context]]
            [ring.util.response :as ring-resp]
            [schema.core :as sc]
            [slingshot.slingshot :refer [throw+ try+]]))

(def ^:private ^:const authn-header "x-authentication")
(def ^:private ^:const authn-param "token")
(def ^:private ^:const internal-subject-key ::rbac-subject)

(defn- token-from-request
  "Given a ring request map, return the JWT token string found in the
  authentication header, token query parameter, or nil if nothing is found."
  [req]
  (or (not-empty (get-in req [:headers authn-header]))
      (not-empty (get-in req [:params authn-param]))
      (not-empty (get-in req [:params (keyword authn-param)]))))

(def ^:private authentication-error-kinds
 #{:puppetlabs.rbac/user-revoked
   :puppetlabs.rbac/token-expired
   :puppetlabs.rbac/token-revoked
   :puppetlabs.rbac/invalid-token})

(defn- build-response
  "Given a status code and a value for the body, return a ring response map with
  that code, the JSON representation of `body` as the body, and a JSON content-type"
  [status-code body]
  (-> (ring-resp/response (json/generate-string body))
    (ring-resp/status status-code)
    (ring-resp/header "Content-Type" "application/json; charset=utf-8")))

(defn- wrap-token-access*
  "Given a reified Rbac Consumer Service and a ring handler, wraps the handler
  with an authentication token check.

  If no token is found, no :subject is set and the handler is called (presumably
  to be blocked by the block-anonymous-access middleware later). If a token is
  found and is invalid or a valid token represents a revoked user, a 401 is
  returned with a relevant error map.

  When the token is provided as a request parameter, this middleware has some notable
  interactions with several ring middlewares. The token parameter is fetched from
  the :params map inside the request, which relies on the ring.middleware.params
  middleware, and checks for the string \"token\", which middlewares like
  ring.middleware.keywords-param middleware may break if run before this middleware.
  So in short, this middleware should be used after the 'wrap-params' middleware but
  before any middleware that might alter the structure of the :params map."
  [rbac-svc handler]
  (let [authn-error? (fn [e] (contains? authentication-error-kinds (:kind e)))]
    (fn [req]
      (if-let [token-str (token-from-request req)]
        (try+
          (let [subject (valid-token->subject rbac-svc token-str)]
            (log/infof "Authenticated subject %s (%s) via authentication token"
                       (:login subject) (:id subject))
            (handler (assoc req internal-subject-key subject)))
          (catch authn-error? e
            (build-response 401 e)))
        ;; No token? Continue without setting a subject.
        (handler req)))))

(def ^:private RbacSubject
  {:id java.util.UUID
   :login sc/Str
   (sc/optional-key :display_name) sc/Str
   (sc/optional-key :email) sc/Str
   (sc/optional-key :last_login) (sc/maybe sc/Str)
   (sc/optional-key :role_ids) [sc/Int]
   (sc/optional-key :inherited_role_ids) [sc/Int]
   (sc/optional-key :group_ids) [java.util.UUID]
   (sc/optional-key :is_superuser) sc/Bool
   (sc/optional-key :is_revoked) sc/Bool
   (sc/optional-key :is_remote) sc/Bool
   (sc/optional-key :is_group) sc/Bool})

(defn- wrap-block-anonymous-access
  "This internal middleware blocks any request that doesn't have a key set for
  an RBAC subject. In other words, if no middleware successfully authenticated
  this request (by cert or session), it should be rejected with a 401.

  If the request does have a valid RBAC subject, it is added to the request map
  under the :subject key for application code to use.

  A :redirect key is also included in the error if appropriate; this redirect is
  *not* sanitized. It is up to the actual processor of the redirect to sanitize
  the URL."
  [handler]
  (fn [req]
    (if-let [subject (get req internal-subject-key)]
      (do
        (sc/validate RbacSubject subject)
        (handler (assoc req :subject subject)))
      (build-response 401 {:kind :puppetlabs.rbac/user-unauthenticated
                           :msg "Route requires authentication"}))))

(defn wrap-token-only-access
  "This middleware should be applied to ring handlers that want to allow *only*
  token-authenticated requests. It takes an RBAC Consumer Service and a ring
  handler to wrap."
  [rbac-svc handler]
  (->> handler
       wrap-block-anonymous-access
       (wrap-token-access* rbac-svc)))
