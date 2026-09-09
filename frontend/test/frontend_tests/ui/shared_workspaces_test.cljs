;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.ui.shared-workspaces-test
  (:require
   ["jsdom" :refer [JSDOM]]
   ["react" :as react]
   ["react-dom/server" :as server]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.main.store :as st]
   [app.main.ui.dashboard.sidebar :as sidebar]
   [app.main.ui.settings.profile :as profile]
   [app.util.globals :as globals]
   [app.util.i18n :as i18n]
   [cljs.test :as t :include-macros true]
   [clojure.string :as str]))

(defn- render
  [component account props]
  (let [previous @st/state
        browser  (JSDOM. "<!doctype html><html><body></body></html>" #js {:url "http://localhost/"})
        document (.-document js/globalThis)
        window   (.-window js/globalThis)]
    (try
      (set! (.-document js/globalThis) (.. browser -window -document))
      (set! (.-window js/globalThis) (.-window browser))
      (swap! st/state assoc :profile account)
      (with-redefs [globals/document (.. browser -window -document)
                    globals/window (.-window browser)
                    i18n/tr (fn ([key] key) ([key & _] key))]
        (server/renderToStaticMarkup (react/createElement component props)))
      (finally
        (reset! st/state previous)
        (set! (.-document js/globalThis) document)
        (set! (.-window js/globalThis) window)
        (.close (.-window browser))))))

(t/deftest restricted-mode-hides-personal-and-create-team-actions
  (let [id (uuid/random)
        account {:id (uuid/random) :fullname "Example User" :email "user@example.com"
                 :default-team-id id :can-create-teams false}
        team {:id id :name "Shared design" :is-default false}
        props #js {:onClose identity :show true :profile account :team team :teams {id team}}]
    (with-redefs [cf/flags (conj cf/flags :shared-workspaces-only)]
      (let [menu (render @#'sidebar/teams-selector-dropdown* account props)]
        (t/is (not (str/includes? menu "dashboard.personal-projects")))
        (t/is (not (str/includes? menu "dashboard.create-new-team")))
        (t/is (str/includes? menu "Shared design"))))))

(t/deftest users-without-shared-membership-see-actionable-instructions
  (let [account {:id (uuid/random) :fullname "Example User" :email "user@example.com"}]
    (with-redefs [cf/flags (conj cf/flags :shared-workspaces-only)]
      (t/is (str/includes? (render profile/profile-page* account #js {})
                           "dashboard.shared-workspace-required")))
    (t/is (not (str/includes? (render profile/profile-page* account #js {})
                              "dashboard.shared-workspace-required")))))
