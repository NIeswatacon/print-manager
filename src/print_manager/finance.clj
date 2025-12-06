(ns print-manager.finance)

;; --- BANCO DE DADOS EM MEMÓRIA (ATOM) ---
;; Estrutura: {:saldo 0.0M :transacoes ()}
;; O estado é mantido neste átomo enquanto o servidor estiver rodando.
(defonce finance-db (atom {:saldo 0.0M
                           :transacoes '()})) ;; Lista encadeada (list)

;; --- FUNÇÕES PURAS (Lógica) ---

(defn criar-transacao [tipo valor descricao]
  {:id        (java.util.UUID/randomUUID)
   :tipo      tipo      ;; :receita ou :despesa
   :valor     (bigdec valor)
   :descricao descricao
   ;; CORREÇÃO: Uso de Java Nativo para não precisar de libs extras
   :data      (java.time.Instant/now)})

(defn aplicar-transacao [db transacao]
  (let [fator (if (= (:tipo transacao) :receita) 1 -1)
        valor-ajustado (* (:valor transacao) fator)]
    (-> db
        (update :saldo + valor-ajustado)
        (update :transacoes conj transacao)))) ;; Adiciona à lista

;; --- FUNÇÕES DE EFEITO (State Management) ---

(defn get-saldo []
  (:saldo @finance-db))

(defn get-extrato []
  (:transacoes @finance-db))

(defn registrar-transacao! [tipo valor descricao]
  (let [tx (criar-transacao tipo valor descricao)]
    (swap! finance-db aplicar-transacao tx)
    tx))

(defn definir-saldo-inicial! [valor]
  (swap! finance-db assoc :saldo (bigdec valor))
  (get-saldo))

;; --- RELATÓRIOS EM MEMÓRIA ---

(defn filtrar-por-periodo [inicio fim transacoes]
  (filter (fn [tx]
            (let [d (:data tx)]
              ;; Compara Instant do Java diretamente
              (and (not (.isBefore d inicio))
                   (not (.isAfter d fim)))))
          transacoes))

(defn calcular-resumo [transacoes]
  (reduce (fn [acc {:keys [tipo valor]}]
            (if (= tipo :receita)
              (update acc :ganho + valor)
              (update acc :gasto + valor)))
          {:ganho 0.0M :gasto 0.0M}
          transacoes))