(ns print-manager.cost-calculator
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]))

;; -----------------------------
;; Specs
;; -----------------------------
(s/def ::tempo-minutos number?)
(s/def ::peso-usado-g number?)
(s/def ::preco-venda (s/nilable number?))
(s/def ::potencia-watts number?)
(s/def ::tarifa-kwh number?)
(s/def ::custo-por-kg number?)

;; -----------------------------
;; Helpers Seguros
;; -----------------------------

(defn safe-double [x]
  (try
    (double (or x 0))
    (catch Exception _ 0.0)))

(defn bd2
  "Converte número para BigDecimal com 2 casas. Retorna 0.00 se for nil."
  [x]
  (if x
    (try
      (-> (format "%.2f" (safe-double x))
          (str/replace "," ".")
          bigdec)
      (catch Exception _ 0.00M))
    0.00M))

;; -----------------------------
;; Funções de Cálculo
;; -----------------------------

(defn calcular-custo-filamento
  [peso-usado-g custo-por-kg]
  (let [peso  (or peso-usado-g 0)
        custo (or custo-por-kg 0)]
    (* peso (/ custo 1000.0))))

(defn calcular-custo-energia
  [tempo-minutos potencia-watts tarifa-kwh]
  (let [tempo  (or tempo-minutos 0)
        watts  (or potencia-watts 0)
        tarifa (or tarifa-kwh 0)]
    (if (and (pos? tempo) (pos? watts))
      (let [horas    (/ (safe-double tempo) 60.0)
            kwh      (/ (* (safe-double watts) horas) 1000.0)
            kwh-real (* kwh 0.5)] ;; Fator de uso real (aquecimento intermitente)
        (* (bigdec kwh-real) (bigdec tarifa)))
      0M)))

(defn calcular-amortizacao
  [tempo-minutos valor-impressora vida-util-horas]
  (let [tempo (/ (or tempo-minutos 0) 60.0)
        valor (or valor-impressora 0)
        vida  (or vida-util-horas 0)]
    (if (pos? vida)
      (* (/ valor vida) tempo)
      0M)))

(defn calcular-custo-total
  [{:keys [custo-filamento custo-energia custo-fixo custo-amortizacao custo-acessorios percentual-falhas]}]
  (let [c-filamento (or custo-filamento 0)
        c-energia   (or custo-energia 0)
        c-fixo      (or custo-fixo 0)
        c-amort     (or custo-amortizacao 0)
        c-acess     (or custo-acessorios 0)
        p-falhas    (or percentual-falhas 15)

        custo-base (+ c-filamento c-energia c-fixo c-amort c-acess)
        margem-falhas (* custo-base (/ p-falhas 100.0))]
    (+ custo-base margem-falhas)))

(defn calcular-preco-consumidor
  [custo-total config]
  (let [markup          (or (:markup config) (:markup-padrao config) 5)
        imposto         (or (:imposto-percentual config) 8)
        taxa-cartao     (or (:taxa-cartao-percentual config) 5)
        custo-anuncio   (or (:custo-anuncio-percentual config) 20)

        custo           (or custo-total 0)
        preco-base      (* custo markup)
        total-percentuais (+ imposto taxa-cartao custo-anuncio)
        divisor         (- 1 (/ total-percentuais 100.0))]

    (if (pos? divisor)
      (/ preco-base divisor)
      preco-base)))

(defn calcular-lucros
  [preco-venda custo-total config]
  (let [pv              (or preco-venda 0)
        ct              (or custo-total 0)
        imposto         (or (:imposto-percentual config) 8)
        taxa-cartao     (or (:taxa-cartao-percentual config) 5)
        custo-anuncio   (or (:custo-anuncio-percentual config) 20)

        lucro-bruto     (- pv ct)
        deducoes        (* pv (/ (+ imposto taxa-cartao custo-anuncio) 100.0))
        lucro-liquido   (- lucro-bruto deducoes)]

    {:lucro-bruto       lucro-bruto
     :lucro-liquido     lucro-liquido
     :margem-percentual (if (pos? pv) (* 100 (/ lucro-liquido pv)) 0)}))

(defn calcular-impressao-completa
  "Função principal de cálculo - Protegida contra nils"
  [impressao config]
  (let [;; Garante valores padrão se a config vier vazia do banco
         potencia-watts (or (:potencia-watts config) (:potencia-impressora-watts config) 1300)
         tarifa-kwh     (or (:custo-kwh config) (:tarifa-kwh config) 0.90M)
         custo-kg       (or (:custo-por-kg config) 0)
         valor-imp      (or (:valor-impressora config) 4500)
         vida-util      (or (:vida-util-horas config) 20000)
         perc-falhas    (or (:percentual-falhas config) 15)

         ;; Dados da impressão
         tempo (or (:tempo-minutos impressao) 0)
         peso  (or (:peso-usado-g impressao) 0)

         ;; Cálculos
         c-filamento (calcular-custo-filamento peso custo-kg)
         c-energia   (calcular-custo-energia tempo potencia-watts tarifa-kwh)
         c-fixo      0M ;; Desabilitado
         c-amort     (calcular-amortizacao tempo valor-imp vida-util)

         c-total     (calcular-custo-total
                      {:custo-filamento     c-filamento
                       :custo-energia       c-energia
                       :custo-fixo          c-fixo
                       :custo-amortizacao   c-amort
                       :custo-acessorios    (or (:custo-acessorios impressao) 0)
                       :percentual-falhas   perc-falhas})

         p-consumidor (calcular-preco-consumidor c-total config)
         p-lojista    (/ p-consumidor 2)
         p-real       (or (:preco-venda impressao) p-consumidor)

         lucros       (calcular-lucros p-real c-total config)]

    ;; Retorna mapa final formatado
    {:custo-filamento           (bd2 c-filamento)
     :custo-energia             (bd2 c-energia)
     :custo-fixo                (bd2 c-fixo)
     :custo-amortizacao         (bd2 c-amort)
     :custo-total               (bd2 c-total)
     :preco-consumidor-sugerido (bd2 p-consumidor)
     :preco-lojista-sugerido    (bd2 p-lojista)
     :preco-venda-real          (bd2 p-real)
     :lucro-bruto               (bd2 (:lucro-bruto lucros))
     :lucro-liquido             (bd2 (:lucro-liquido lucros))
     :margem-percentual         (bd2 (:margem-percentual lucros))}))