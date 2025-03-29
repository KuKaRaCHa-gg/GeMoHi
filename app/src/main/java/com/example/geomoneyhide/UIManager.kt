package com.example.geomoneyhide

import android.app.AlertDialog
import android.app.Dialog
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast

/**
 * Gestionnaire d'interface utilisateur pour le jeu GeoMoneyHide
 *
 * Cette classe est responsable de:
 * - Afficher et gérer les boîtes de dialogue pour les réglages et informations
 * - Permettre la personnalisation des couleurs des zones
 * - Présenter les règles du jeu et les crédits
 *
 * @param context L'activité principale pour le contexte d'interface
 * @param gameState L'état du jeu pour accéder et modifier les préférences
 * @param gridManager Le gestionnaire de grille pour mettre à jour les couleurs
 */
class UIManager(
    private val context: MapsActivity,
    private val gameState: GameState,
    private val gridManager: GridManager
) {

    /**
     * Affiche une boîte de dialogue permettant à l'utilisateur de personnaliser
     * la couleur des zones explorées avec des curseurs RGBA
     */
    fun showColorPickerDialog() {
        val dialog = Dialog(context)
        dialog.setContentView(R.layout.color_picker_dialog)
        dialog.setTitle("Choisissez une couleur")

        // Récupère les éléments d'interface du dialogue
        val redSeekBar = dialog.findViewById<SeekBar>(R.id.seekbar_red)
        val greenSeekBar = dialog.findViewById<SeekBar>(R.id.seekbar_green)
        val blueSeekBar = dialog.findViewById<SeekBar>(R.id.seekbar_blue)
        val alphaSeekBar = dialog.findViewById<SeekBar>(R.id.seekbar_alpha)
        val colorPreview = dialog.findViewById<View>(R.id.color_preview)
        val applyButton = dialog.findViewById<Button>(R.id.button_apply)

        // Extrait les composantes RGBA de la couleur actuelle
        val alpha = (gameState.circleColor shr 24) and 0xFF  // Décalage de 24 bits et masque
        val red = (gameState.circleColor shr 16) and 0xFF    // Décalage de 16 bits et masque
        val green = (gameState.circleColor shr 8) and 0xFF   // Décalage de 8 bits et masque
        val blue = gameState.circleColor and 0xFF            // Masque uniquement

        // Initialise les curseurs avec les valeurs actuelles
        redSeekBar.progress = red
        greenSeekBar.progress = green
        blueSeekBar.progress = blue
        alphaSeekBar.progress = alpha

        // Met à jour l'aperçu de couleur initial
        updateColorPreview(colorPreview, redSeekBar.progress, greenSeekBar.progress,
            blueSeekBar.progress, alphaSeekBar.progress)

        // Configure les écouteurs de changement pour tous les curseurs
        val seekBarChangeListener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                // Met à jour l'aperçu en temps réel pendant que l'utilisateur ajuste les curseurs
                updateColorPreview(colorPreview, redSeekBar.progress, greenSeekBar.progress,
                    blueSeekBar.progress, alphaSeekBar.progress)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}  // Non utilisé
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}   // Non utilisé
        }

        // Applique l'écouteur à tous les curseurs
        redSeekBar.setOnSeekBarChangeListener(seekBarChangeListener)
        greenSeekBar.setOnSeekBarChangeListener(seekBarChangeListener)
        blueSeekBar.setOnSeekBarChangeListener(seekBarChangeListener)
        alphaSeekBar.setOnSeekBarChangeListener(seekBarChangeListener)

        // Configure le bouton d'application
        applyButton.setOnClickListener {
            // Crée une nouvelle couleur en combinant les composantes RGBA
            // avec des opérations de décalage binaire et OR
            gameState.circleColor = (alphaSeekBar.progress shl 24) or
                    (redSeekBar.progress shl 16) or
                    (greenSeekBar.progress shl 8) or
                    blueSeekBar.progress

            // Met à jour toutes les cellules avec la nouvelle couleur
            gridManager.updateCellColors()

            // Sauvegarde la préférence de couleur
            gameState.saveGameData()

            // Confirme le changement à l'utilisateur
            Toast.makeText(context, "Couleur des zones changée !", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }

        // Affiche la boîte de dialogue
        dialog.show()
    }

    /**
     * Met à jour la vue d'aperçu avec la couleur composée des valeurs RGBA spécifiées
     *
     * @param view La vue à mettre à jour (rectangle d'aperçu)
     * @param red Valeur de rouge (0-255)
     * @param green Valeur de vert (0-255)
     * @param blue Valeur de bleu (0-255)
     * @param alpha Valeur de transparence (0-255)
     */
    private fun updateColorPreview(view: View, red: Int, green: Int, blue: Int, alpha: Int) {
        val color = Color.argb(alpha, red, green, blue)
        view.setBackgroundColor(color)
    }

    /**
     * Affiche une boîte de dialogue présentant les règles du jeu
     */
    fun showRulesDialog() {
        val builder = AlertDialog.Builder(context)
        val inflater = LayoutInflater.from(context)
        val view = inflater.inflate(R.layout.rules_dialog, null)

        builder.setView(view)
        builder.setTitle("Règles du jeu")
        builder.setPositiveButton("OK") { dialog, _ -> dialog.dismiss() }

        // Configure le texte des règles
        val rulesTextView = view.findViewById<TextView>(R.id.rules_text)
        rulesTextView.text = """
            Bienvenue dans GeoMoneyHide !

            1. Explorez le monde réel pour découvrir de nouvelles zones
            2. Chaque zone de 1000m x 1000m explorée est marquée sur la carte
            3. Collectez des pièces en vous approchant d'elles
            4. Découvrir une nouvelle zone collecte automatiquement toutes les pièces de cette zone
            5. Personnalisez la couleur de vos zones explorées

            Objectif : Explorer le plus de terrain et collecter le plus de pièces possible !
        """.trimIndent()

        // Affiche la boîte de dialogue
        builder.create().show()
    }

    /**
     * Affiche une boîte de dialogue présentant les crédits du jeu
     */
    fun showCreditsDialog() {
        val builder = AlertDialog.Builder(context)
        val inflater = LayoutInflater.from(context)
        val view = inflater.inflate(R.layout.credits_dialog, null)

        builder.setView(view)
        builder.setTitle("Crédits")
        builder.setPositiveButton("OK") { dialog, _ -> dialog.dismiss() }

        // Configure le texte des crédits
        val creditsTextView = view.findViewById<TextView>(R.id.credits_text)
        creditsTextView.text = """
            GeoMoneyHide
            Version 1.0

            Développé par:
            - Daniil Minevich
            - IUT LAVAL

            Utilisant:
            - Google Maps API
            - Android Location Services
            - Bibliothèque JTS
            - Android Studio

            © 2025 Tous droits réservés
        """.trimIndent()

        // Affiche la boîte de dialogue
        builder.create().show()
    }
}