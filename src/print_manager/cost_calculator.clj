(ns print-manager.cost-calculator
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]))


;; Specs para validação
(s/def ::tempo-minutos pos-int?)
(s/def ::peso-usado-g (s/and number? pos?))
(s/def ::preco-venda (s/nilable number?))

(s/def ::potencia-watts pos-int?)
(s/def ::tarifa-kwh (s/and number? pos?))
;; >>> FALTAVA ESTE SPEC <<<
(s/def ::custo-por-kg (s/and number? pos?))

(defn bd2
  "Converte número para BigDecimal com 2 casas, independente do locale."
  [x]
  (-> (format "%.2f" (double x)) ; gera string "33,06" ou "33.06"
      (str/replace "," ".")      ; garante ponto como separador
      bigdec))


(defn calcular-custo-filamento
  "Calcula o custo do filamento usado na impressão"
  [peso-usado-g custo-por-kg]
  {:pre [(s/valid? ::peso-usado-g peso-usado-g)
         (s/valid? ::custo-por-kg custo-por-kg)]}
  (* peso-usado-g (/ custo-por-kg 1000)))

(defn calcular-custo-energia
  "Calcula o custo de energia com base no tempo (min), potência (W) e tarifa (R$/kWh)."
  [tempo-minutos potencia-watts tarifa-kwh]

  ;; Validação simples, sem spec/assert
  (when-not (and (number? tempo-minutos)
                 (>= tempo-minutos 0)
                 (number? potencia-watts)
                 (pos? potencia-watts)
                 (number? tarifa-kwh)
                 (pos? tarifa-kwh))
    (throw (ex-info "Parâmetros inválidos para cálculo de energia"
                    {:tempo-minutos tempo-minutos
                     :potencia-watts potencia-watts
                     :tarifa-kwh tarifa-kwh})))

  (let [horas (/ (double tempo-minutos) 60.0)
        kwh   (/ (* (double potencia-watts) horas) 1000.0)
        kwh-real (* kwh 0.5)]
    (* (bigdec kwh-real) (bigdec tarifa-kwh))))

(defn calcular-custo-fixo
  "Custo fixo desabilitado por enquanto → sempre retorna 0M."
  [_ _]
  0M)

(defn calcular-amortizacao
  "Calcula amortização da impressora. Se valores não existirem, retorna 0."
  [tempo-minutos valor-impressora vida-util-horas]
  (let [tempo-horas (/ tempo-minutos 60.0)
        valor (or valor-impressora 0)
        vida  (or vida-util-horas 0)]
    (if (pos? vida)
      (* (/ valor vida) tempo-horas)
      0M))) ;; se vida for zero ou nil → amortização = 0

(defn calcular-custo-total
  "Calcula o custo total da impressão incluindo margem de falhas"
  [{:keys [custo-filamento
           custo-energia
           custo-fixo
           custo-amortizacao
           custo-acessorios
           percentual-falhas]
    :or {custo-acessorios 0
         percentual-falhas 15}}]
  (let [custo-base (+ custo-filamento
                      custo-energia
                      custo-fixo
                      custo-amortizacao
                      custo-acessorios)
        margem-falhas (* custo-base (/ percentual-falhas 100.0))]
    (+ custo-base margem-falhas)))

(defn calcular-preco-consumidor
  "Calcula o preço de venda ao consumidor final
   Baseado na planilha STLFlix com markup, impostos, taxas"
  [custo-total {:keys [markup
                       imposto-percentual
                       taxa-cartao-percentual
                       custo-anuncio-percentual]
                :or {markup 5
                     imposto-percentual 8
                     taxa-cartao-percentual 5
                     custo-anuncio-percentual 20}}]
  (let [preco-base (* custo-total markup)
        total-percentuais (+ imposto-percentual
                             taxa-cartao-percentual
                             custo-anuncio-percentual)
        divisor (- 1 (/ total-percentuais 100.0))]
    (/ preco-base divisor)))

  (defn calcular-preco-lojista
    "Calcula o preço de venda para lojista (50% do preço consumidor)"
    [preco-consumidor]
    (/ preco-consumidor 2))

  (defn calcular-lucros
    "Calcula lucro bruto e líquido"
    [preco-venda custo-total {:keys [imposto-percentual
                                     taxa-cartao-percentual
                                     custo-anuncio-percentual]
                              :or {imposto-percentual 8
                                   taxa-cartao-percentual 5
                                   custo-anuncio-percentual 20}}]
    (let [lucro-bruto (- preco-venda custo-total)
          total-deducoes (* preco-venda
                            (/ (+ imposto-percentual
                                  taxa-cartao-percentual
                                  custo-anuncio-percentual)
                               100.0))
          lucro-liquido (- lucro-bruto total-deducoes)]
      {:lucro-bruto lucro-bruto
       :lucro-liquido lucro-liquido
       :margem-percentual (if (pos? preco-venda)
                            (* 100 (/ lucro-liquido preco-venda))
                            0)}))

  (defn calcular-impressao-completa
    "Calcula todos os custos e preços de uma impressão"
    [impressao config]
    (let [;; garante que vamos achar a potência, mesmo que a chave seja outra
          potencia-watts (or (:potencia-watts config)
                             (:potencia-impressora-watts config)
                             1300)          ;; fallback seguro

          tarifa-kwh     (or (:custo-kwh config)
                             (:tarifa-kwh config)
                             0.90M)

          ;; Custos individuais
          custo-filamento (calcular-custo-filamento
                            (:peso-usado-g impressao)
                            (:custo-por-kg config))

          custo-energia (calcular-custo-energia
                          (:tempo-minutos impressao)
                          potencia-watts
                          tarifa-kwh)

          custo-fixo 0M

          custo-amortizacao (calcular-amortizacao
                              (:tempo-minutos impressao)
                              (:valor-impressora config)
                              (:vida-util-horas config))

          ;; Custo total
          custo-total (calcular-custo-total
                        {:custo-filamento     custo-filamento
                         :custo-energia       custo-energia
                         :custo-fixo          custo-fixo
                         :custo-amortizacao   custo-amortizacao
                         :custo-acessorios    (or (:custo-acessorios impressao) 0)
                         :percentual-falhas   (:percentual-falhas config)})

          ;; Preços sugeridos
          preco-consumidor (calcular-preco-consumidor custo-total config)
          preco-lojista    (calcular-preco-lojista preco-consumidor)

          ;; Usar preço real se fornecido, senão usar sugestão
          preco-venda (or (:preco-venda impressao) preco-consumidor)

          ;; Lucros
          lucros (calcular-lucros preco-venda custo-total config)]

      {:custo-filamento           (bd2 custo-filamento)
       :custo-energia             (bd2 custo-energia)
       :custo-fixo                (bd2 custo-fixo)
       :custo-amortizacao         (bd2 custo-amortizacao)
       :custo-total               (bd2 custo-total)
       :preco-consumidor-sugerido (bd2 preco-consumidor)
       :preco-lojista-sugerido    (bd2 preco-lojista)
       :preco-venda-real          (bd2 preco-venda)
       :lucro-bruto               (bd2 (:lucro-bruto lucros))
       :lucro-liquido             (bd2 (:lucro-liquido lucros))
       :margem-percentual         (bd2 (:margem-percentual lucros))}))
