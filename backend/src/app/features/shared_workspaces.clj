;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.features.shared-workspaces
  (:require
   [app.common.exceptions :as ex]
   [app.config :as cf]
   [app.db :as db]))

(defn enabled? [] (contains? cf/flags :shared-workspaces-only))

(defn check-resource!
  [cfg kind id]
  (when (enabled?)
    (let [sql (case kind
                :team "SELECT is_default FROM team WHERE id = ?"
                :project "SELECT t.is_default FROM project p JOIN team t ON t.id = p.team_id WHERE p.id = ?"
                :file "SELECT t.is_default FROM file f JOIN project p ON p.id = f.project_id JOIN team t ON t.id = p.team_id WHERE f.id = ?")]
      (when (:is-default (db/exec-one! cfg [sql id]))
        (ex/raise :type :not-found :code :object-not-found)))))

(defn can-create-team?
  [account]
  (or (not (enabled?))
      (and (:is-active account)
           (not (:is-blocked account))
           (contains? (cf/get :admins #{}) (:email account)))))

(defn check-create-team!
  [cfg profile-id personal?]
  (when (and (enabled?)
             (or personal? (not (can-create-team? (db/get-by-id cfg :profile profile-id)))))
    (ex/raise :type :restriction :code :shared-workspaces-only)))

(defn resolve-defaults
  [cfg profile]
  (if-not (enabled?)
    profile
    (let [target (db/exec-one! cfg
                               ["SELECT t.id AS team_id, p.id AS project_id
                                  FROM team_profile_rel r JOIN team t ON t.id = r.team_id
                                  JOIN project p ON p.team_id = t.id AND p.is_default
                                 WHERE r.profile_id = ? AND NOT t.is_default
                                   AND t.deleted_at IS NULL AND p.deleted_at IS NULL
                                 ORDER BY (t.id = ?) DESC, r.created_at, t.id LIMIT 1"
                                (:id profile) (:default-team-id profile)])]
      (if target
        (assoc profile :default-team-id (:team-id target) :default-project-id (:project-id target))
        (dissoc profile :default-team-id :default-project-id)))))
